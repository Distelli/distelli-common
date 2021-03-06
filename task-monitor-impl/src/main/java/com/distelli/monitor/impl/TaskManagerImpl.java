package com.distelli.monitor.impl;

import com.distelli.jackson.transform.TransformModule;
import com.distelli.monitor.Monitor;
import com.distelli.monitor.MonitorInfo;
import com.distelli.monitor.Sequence;
import com.distelli.monitor.TaskBuilder;
import com.distelli.monitor.TaskContext;
import com.distelli.monitor.TaskFunction;
import com.distelli.monitor.TaskInfo;
import com.distelli.monitor.TaskManager;
import com.distelli.monitor.TaskState;
import com.distelli.persistence.AttrType;
import com.distelli.persistence.Index;
import com.distelli.persistence.FilterCondExpr;
import com.distelli.persistence.PageIterator;
import com.distelli.persistence.TableDescription;
import com.distelli.persistence.UpdateItemBuilder;
import com.distelli.utils.CompactUUID;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.persistence.RollbackException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.distelli.persistence.AttrType;
import java.util.function.Predicate;
import java.util.function.Consumer;
import static com.distelli.utils.LongSortKey.*;

/**
 * If we loose DB connection or the JVM is `kill -9`ed, then tasks in these states will
 * move out of these states by these mechanisms:
 *
 *  QUEUED: startRunnableTasks()
 *  RUNNING: releaseLocksForMonitorId() when broken monitor is found.
 *  WAITING_FOR_PREREQUISITE: releaseLocks("_TASK"+longToSortKey(prerequisiteId))
 *  WAITING_FOR_INTERVAL: releaseLocksForMonitorId() when broken monitor is found.
 *  WAITING_FOR_LOCK: releaseLocks(lockId)
 */
@Singleton
public class TaskManagerImpl implements TaskManager {
    private static final int POLL_INTERVAL_MS = 10000;
    private static final int MAX_TASKS_IN_INTERVAL = 10;
    // Every POLL_INTERVAL_MS*CLEANUP_INTERVALS we perform a scan
    // to see if we need to do some cleanup:
    private static final int CLEANUP_INTERVALS = 30;
    private static final String TASK_ID_NONE = "#";
    private static final String MONITOR_ID_QUEUED = "#";
    private static final String MONITOR_ID_WAITING = "$";
    private static final Logger LOG = LoggerFactory.getLogger(TaskManagerImpl.class);

    @Inject
    private Monitor _monitor;
    @Inject
    private ScheduledExecutorService _executor;
    @Inject
    private Map<String, TaskFunction> _taskFunctions;
    @Inject
    private Sequence _sequence;

    // constants (no locking needed):
    private Index<Lock> _locks;
    private Index<Lock> _locksForMonitor;
    private Index<Task> _tasks;
    private Index<Task> _tasksForMonitor;
    private Index<Task> _tasksForEntity;
    private Index<Task> _nonTerminalTasksForEntity;
    private final ObjectMapper _om = new ObjectMapper();
    private final Map<Long, DelayedTask> _delayedTasks =
        new ConcurrentHashMap<>();
    private Semaphore _capacity;
    private int _maxCapacity;

    // synchronized(_queuedTasks):
    private Set<Long> _taskQueue = new LinkedHashSet<>();
    // synchronized(this):
    private ScheduledFuture<?> _monitorTasks;
    // synchronized(this):
    private Set<Future<?>> _spawnedFutures = new HashSet<>();
    // synchronized(this):
    private long _lastRunTask = 0;
    // synchronized(this):
    private Future<?> _runNextTaskFuture = null;

    private Set<Consumer<TaskInfo>> _onTerminalState =
        Collections.synchronizedSet(new HashSet<Consumer<TaskInfo>>());

    private Predicate<TaskInfo> _taskMatches;

    private AtomicInteger _pollCount = new AtomicInteger(
        ThreadLocalRandom.current().nextInt(0, CLEANUP_INTERVALS-1));

    private static class DelayedTask {
        private DelayedTask(long millisRemaining) {
            this.millisTimeBegin = milliTime();
            this.millisRemaining = millisRemaining;
        }
        public long millisTimeBegin;
        public long millisRemaining;
    }

    public static class TasksTable {
        public static TableDescription getTableDescription() {
            return TableDescription.builder()
                .tableName("monitor-tasks")
                // Query on task id:
                .index((idx) -> idx
                       .readCapacity(2L)
                       .writeCapacity(12L)
                       .hashKey("id", AttrType.NUM))
                // Query on monitor ids, or "special" state:
                //    '#' - Runnable
                //    '$' - Waiting on lock/prequisite
                .index((idx) -> idx
                       .writeCapacity(6L)
                       .indexName("mid-id-index")
                       .hashKey("mid", AttrType.STR)
                       .rangeKey("id", AttrType.NUM))
                // Query on entities:
                .index((idx) -> idx
                       .writeCapacity(6L)
                       .indexName("ety-eid-index")
                       .hashKey("ety", AttrType.STR)
                       .rangeKey("eid", AttrType.STR))
                // Same as above, but only include non-terminals:
                .index((idx) -> idx
                       .writeCapacity(6L)
                       .indexName("ntty-ntid-index")
                       .hashKey("ntty", AttrType.STR)
                       .rangeKey("ntid", AttrType.STR))
                .build();
        }
    }

    public static class LocksTable {
        // Entries in this table are either locks or tasks waiting
        // on a lock.
        public static TableDescription getTableDescription() {
            return TableDescription.builder()
                .tableName("monitor-locks")
                .index((idx) -> idx
                       // We do a LOT of writing on this table!
                       .writeCapacity(8L)
                       .hashKey("lid", AttrType.STR)
                       // "actual" locks have tid=TASK_ID_NONE:
                       .rangeKey("tid", AttrType.STR))
                // Query on monitor ids:
                .index((idx) -> idx
                       .writeCapacity(3L)
                       .indexName("mid-index")
                       .hashKey("mid", AttrType.STR))
                .build();
        }
    }

    @Override
    public List<? extends TaskInfo> getTasksByEntityType(String entityType, PageIterator iter) {
        return getTasksByEntityType(entityType, null, iter);
    }

    @Override
    public List<? extends TaskInfo> getTasksByEntityType(
        String entityType, String entityIdBeginsWith, PageIterator iter)
    {
        if ( null == entityIdBeginsWith || "".equals(entityIdBeginsWith) ) {
            return _tasksForEntity.queryItems(entityType, iter).list();
        }
        return _tasksForEntity.queryItems(entityType, iter)
            .beginsWith(entityIdBeginsWith)
            .list();
    }

    @Override
    public List<? extends TaskInfo> getNonTerminalTasksByEntityIdBeginsWith(
        String entityType, String taskIdBeginsWith, PageIterator iter)
    {

        if ( null == taskIdBeginsWith || "".equals(taskIdBeginsWith) ) {
            return _nonTerminalTasksForEntity.queryItems(entityType, iter)
                .list();
        }
        return _nonTerminalTasksForEntity.queryItems(entityType, iter)
            .beginsWith(taskIdBeginsWith)
            .list();
    }

    @Override
    public List<? extends TaskInfo> getNonTerminalTasks(PageIterator iter) {
        return _tasksForMonitor.scanItems(iter);
    }

    @Override
    public List<? extends TaskInfo> getAllTasks(PageIterator iter) {
        return _tasks.scanItems(iter);
    }

    @Override
    public TaskInfo getTask(Long taskId) {
        return _tasks.getItem(taskId, null);
    }

    @Override
    public TaskBuilder createTask() {
        return new TaskBuilderImpl() {
            @Override
            public TaskInfo build() {
                Task task = (Task)super.build();
                task.taskId = _sequence.next(_tasks.getTableName());
                return task;
            }
        };
    }

    @Override
    public void addTask(TaskInfo taskInfo) {
        if ( ! (taskInfo instanceof Task) ) {
            throw new IllegalArgumentException("TaskInfo must be created from TaskManager.createTask()");
        }
        Task task = (Task)taskInfo;
        if ( null == task.getEntityType() ) {
            throw new IllegalArgumentException("missing task.entityType");
        }
        if ( null == task.getEntityId() ) {
            throw new IllegalArgumentException("missing task.entityId");
        }
        if ( null == task.getTaskId() ) {
            throw new IllegalStateException("_sequence.next("+_tasks.getTableName()+") returned null!");
        }
        TaskFunction taskFunction =
            _taskFunctions.get(task.getEntityType());
        if ( null == taskFunction ) {
            throw new IllegalArgumentException(
                "missing TaskFunction for task.entityType="+task.getEntityType());
        }
        if ( null == task.getMillisecondsRemaining() ) {
            task.taskState = TaskState.QUEUED;
        } else {
            task.taskState = TaskState.WAITING_FOR_INTERVAL;
        }
        task.monitorId = MONITOR_ID_QUEUED;
        // Reset the task (just in case):
        task.startTime = null;
        task.endTime = null;
        task.errorMessage = null;
        task.errorId = null;
        task.errorMessageStackTrace = null;
        task.runCount = 0L;
        task.canceledBy = null;
        // Save the task:
        _tasks.putItemOrThrow(task);
        // Dispatch:
        submitRunTask(task.getTaskId());
        submit(this::runNextTask);
    }

    @Override
    public void deleteTask(long taskId) throws IllegalStateException {
        try {
            _tasks.deleteItem(
                taskId, null, (expr) ->
                expr.or(
                    expr.not(expr.exists("id")),
                    expr.or(
                        expr.not(expr.exists("mid")),
                        expr.in(
                            "mid",
                            Arrays.asList(MONITOR_ID_QUEUED, MONITOR_ID_WAITING)))));
        } catch ( RollbackException ex ) {
            throw new IllegalStateException("Attempt to deleteTask("+taskId+") which is currently locked");
        }
    }

    // Marks a task as "to be canceled":
    @Override
    public void cancelTask(String canceledBy, long taskId) {
        if ( null == canceledBy ) throw new IllegalArgumentException("canceledBy may not be null");
        try {
            _tasks.updateItem(taskId, null)
                .set("cancel", AttrType.STR, canceledBy)
                .when((expr) -> expr.exists("mid"));
        } catch ( RollbackException ex ) {
            LOG.debug("Attempt to cancel taskId="+taskId+" that is in a final state, ignoring");
            return;
        }
        try {
            _tasks.updateItem(taskId, null)
                .set("mid", AttrType.STR, MONITOR_ID_QUEUED)
                .set("stat", AttrType.STR, toString(TaskState.QUEUED))
                .when((expr) -> expr.beginsWith("mid", "$"));
        } catch ( RollbackException ex ) {
            return;
        }
        // We moved the task out of waiting, so let's execute it:
        submitRunTask(taskId);
        submit(this::runNextTask);
    }

    @Override
    public synchronized void monitorTaskQueue() {
        monitorTaskQueueFor(null);
    }

    @Override
    public synchronized void monitorTaskQueueFor(
        Predicate<TaskInfo> taskMatches)
    {
        _taskMatches = ( null == taskMatches ) ? (info) -> true : taskMatches;
        if ( null != _monitorTasks ) return;
        if ( null == _executor ) return;
        _monitorTasks = _executor.scheduleAtFixedRate(
            this::startRunnableTasks,
            ThreadLocalRandom.current().nextLong(POLL_INTERVAL_MS),
            POLL_INTERVAL_MS,
            TimeUnit.MILLISECONDS);
    }

    @Override
    public void addOnTerminalState(Consumer<TaskInfo> onTerminalState) {
        _onTerminalState.add(onTerminalState);
    }

    @Override
    public void removeOnTerminalState(Consumer<TaskInfo> onTerminalState) {
        _onTerminalState.remove(onTerminalState);
    }

    @Override
    public void stopTaskQueueMonitor(boolean mayInterruptIfRunning) {
        Set<Future<?>> allFutures = new HashSet<>();
        synchronized ( this ) {
            if ( null == _monitorTasks ) return;
            _monitorTasks.cancel(false);
            allFutures.add(_monitorTasks);
            _monitorTasks = null;
        }
        for ( Long taskId : _delayedTasks.keySet() ) {
            updateDelayedTask(taskId, null);
        }
        synchronized ( this ) {
            if ( null != _monitorTasks ) return;
            for ( Future<?> future : _spawnedFutures ) {
                future.cancel(mayInterruptIfRunning);
                allFutures.add(future);
            }
            _spawnedFutures.clear();
        }
        try {
            // Ensure cancelation eventually occurs:
            for ( long seconds = 60;
                  ! _capacity.tryAcquire(_maxCapacity, seconds, TimeUnit.SECONDS);
                  seconds = seconds / 2)
            {
                for ( Future<?> future : allFutures ) {
                    // Force!
                    future.cancel(true);
                }
                if ( seconds <= 0 ) {
                    LOG.error("Failed to cancel task threads!");
                    return;
                }
            }
            _capacity.release(_maxCapacity);
        } catch ( InterruptedException ex ) {
            Thread.currentThread().interrupt();
            return;
        }
    }

    @Override
    public void updateTask(byte[] updateData, long taskId) {
        if ( null == updateData ) throw new IllegalArgumentException("updateData may not be null");
        try {
            _tasks.updateItem(taskId, null)
                .set("upd", AttrType.BIN, updateData)
                .when((expr) -> expr.exists("mid"));
        } catch ( RollbackException ex ) {
            LOG.debug("Attempt to set updateData on taskId="+taskId+" that is in a final state, ignoring");
            return;
        }
        try {
            _tasks.updateItem(taskId, null)
                .set("mid", AttrType.STR, MONITOR_ID_QUEUED)
                .set("stat", AttrType.STR, toString(TaskState.QUEUED))
                .when((expr) -> expr.beginsWith("mid", "$"));
        } catch ( RollbackException ex ) {
            return;
        }
        // We moved the task out of waiting, so let's execute it:
        submitRunTask(taskId);
        submit(this::runNextTask);
    }

    public void releaseLocksForMonitorId(String monitorId) throws InterruptedException {
        LOG.debug("Releasing locks for monitorId="+monitorId);
        List<Long> taskIdsToRun = new ArrayList<>();
        // Release locks on the "locks" table:
        for ( PageIterator iter : new PageIterator() ) {
            for ( Lock lock : _locksForMonitor.queryItems(monitorId, iter).list() ) {
                // Mark next task as runnable:
                unblockWaitingTasks(lock.lockId, monitorId, taskIdsToRun, false);
                // Remove the lock:
                try {
                    _locks.deleteItem(lock.lockId, TASK_ID_NONE,
                                      (expr) -> expr.eq("mid", monitorId));
                } catch ( RollbackException ex ) {
                    LOG.debug("LostLockException: releaseLock="+lock.lockId+" for monitorId="+monitorId+" taskId="+
                              lock.runningTaskId);
                }
                // Do not immediately dispatch these blocked tasks, so the next code can
                // resume the task that WAS running previously.
                // for ( Long taskId : taskIdsToRun ) {
                //    submitRunTask(taskId);
                // }
                taskIdsToRun.clear();
            }
        }
        // Put the tasks back into a runnable state:
        boolean taskSubmitted = false;
        for ( PageIterator iter : new PageIterator() ) {
            for ( Task task : _tasksForMonitor.queryItems(monitorId, iter).list() ) {
                try {
                    _tasks.updateItem(task.getTaskId(), null)
                        .set("mid", AttrType.STR, MONITOR_ID_QUEUED)
                        .set("stat", AttrType.STR, toString(TaskState.QUEUED))
                        .when((expr) -> expr.eq("mid", monitorId));
                } catch ( RollbackException ex ) {
                    LOG.debug("LostLockException: releaseLocksForMonitorId="+monitorId+" taskId="+
                              task.getTaskId());
                    continue;
                }
                submitRunTask(task.getTaskId());
                taskSubmitted = true;
            }
        }
        if ( taskSubmitted ) {
            submit(this::runNextTask);
        }
    }

    @Inject
    protected TaskManagerImpl() {}

    @Inject
    protected void init(Index.Factory indexFactory, ScheduledExecutorService executorService) {
        // TODO: Make this tunable with a guice configuration:
        _maxCapacity = 5;
        if ( executorService instanceof ScheduledThreadPoolExecutor ) {
            _maxCapacity = ((ScheduledThreadPoolExecutor)executorService).getCorePoolSize() - 1;
            if ( _maxCapacity <= 0 ) {
                _maxCapacity = 1;
                LOG.error("Detected ScheduledThreadPoolExecutor with corePoolSize <=1, thread starvation is likely.");
            } else if ( _maxCapacity > 10 ) _maxCapacity--;
        }
        _capacity = new Semaphore(_maxCapacity);

        _om.registerModule(createTransforms(new TransformModule()));

        String[] noEncrypt = new String[]{"cnt", "agn", "tic", "upd"};
        _tasks = indexFactory.create(Task.class)
            .withTableDescription(TasksTable.getTableDescription())
            .withConvertValue(_om::convertValue)
            .withNoEncrypt(noEncrypt)
            .build();

        _tasksForMonitor = indexFactory.create(Task.class)
            .withTableDescription(TasksTable.getTableDescription(), "mid-id-index")
            .withConvertValue(_om::convertValue)
            .withNoEncrypt(noEncrypt)
            .build();

        _tasksForEntity = indexFactory.create(Task.class)
            .withTableDescription(TasksTable.getTableDescription(), "ety-eid-index")
            .withConvertValue(_om::convertValue)
            .withNoEncrypt(noEncrypt)
            .build();

        _nonTerminalTasksForEntity = indexFactory.create(Task.class)
            .withTableDescription(TasksTable.getTableDescription(), "ntty-ntid-index")
            .withConvertValue(_om::convertValue)
            .withNoEncrypt(noEncrypt)
            .build();

        noEncrypt = new String[]{"mid", "agn", "rtid"};
        _locks = indexFactory.create(Lock.class)
            .withNoEncrypt(noEncrypt)
            .withTableDescription(LocksTable.getTableDescription())
            .withConvertValue(_om::convertValue)
            .build();

        _locksForMonitor = indexFactory.create(Lock.class)
            .withNoEncrypt(noEncrypt)
            .withTableDescription(LocksTable.getTableDescription(), "mid-index")
            .withConvertValue(_om::convertValue)
            .build();
    }

    private TransformModule createTransforms(TransformModule module) {
        module.createTransform(Task.class)
            .put("id", Long.class, "taskId")
            .put("ety", String.class, "entityType")
            .put("eid", String.class, TaskManagerImpl::toEid, TaskManagerImpl::fromEid)
            .put("ntty", String.class, TaskManagerImpl::toNtty)
            .put("ntid", String.class, TaskManagerImpl::toNtid) // non-terminal state?
            .put("stat", String.class, TaskManagerImpl::toState, TaskManagerImpl::fromState)
            .put("lids", new TypeReference<Set<String>>(){}, "lockIds")
            .put("preq", new TypeReference<Set<Long>>(){}, "prerequisiteTaskIds")
            .put("any", Boolean.class, "anyPrerequisiteTaskId")
            .put("mid", String.class, "monitorId")
            .put("upd", byte[].class, "updateData")
            .put("st8", byte[].class, "checkpointData")
            .put("err", String.class, "errorMessage")
            .put("errT", String.class, "errorMessageStackTrace")
            .put("errId", String.class, "errorId")
            .put("ts", Long.class, "startTime")
            .put("tf", Long.class, "endTime")
            .put("cnt", Long.class, "runCount")
            .put("agn", Long.class, "requeues")
            .put("tic", Long.class, "millisecondsRemaining")
            .put("cancel", String.class, "canceledBy");
        module.createTransform(Lock.class)
            .put("lid", String.class, "lockId")
            .put("tid", String.class, "taskId")
            .put("rtid", Long.class, "runningTaskId")
            .put("mid", String.class, "monitorId")
            .put("agn", Long.class, "tasksQueued");
        return module;
    }

    private static String toState(Task task) {
        return toString(task.getTaskState());
    }

    private static void fromState(Task task, String state) {
        task.taskState = toTaskState(state);
    }

    private void onTerminalState(TaskInfo terminalTask) {
        synchronized ( _onTerminalState ) {
            for ( Consumer<TaskInfo> consumer : _onTerminalState ) {
                try {
                    consumer.accept(terminalTask);
                } catch ( Throwable ex ) {
                    LOG.error(
                        "OnTerminalState["+consumer+"]("+terminalTask+") FAILED: "+ex.getMessage(),
                        ex);
                }
            }
        }
    }

    private static String toString(TaskState taskState) {
        if ( null == taskState ) return null;
        switch ( taskState ) {
        case QUEUED: return "Q";
        case RUNNING: return "R";
        case WAITING_FOR_INTERVAL: return "T";
        case WAITING_FOR_PREREQUISITE: return "N";
        case WAITING_FOR_LOCK: return "L";
        case FAILED: return "F";
        case SUCCESS: return "S";
        case CANCELED: return "C";
        }
        throw new UnsupportedOperationException(
            "taskState="+taskState+" is not supported in TaskManagerImpl");
    }

    private static TaskState toTaskState(String state) {
        if ( null == state ) return null;
        switch ( state ) {
        case "Q": return TaskState.QUEUED;
        case "R": return TaskState.RUNNING;
        case "T": return TaskState.WAITING_FOR_INTERVAL;
        case "N": return TaskState.WAITING_FOR_PREREQUISITE;
        case "L": return TaskState.WAITING_FOR_LOCK;
        case "F": return TaskState.FAILED;
        case "S": return TaskState.SUCCESS;
        case "C": return TaskState.CANCELED;
        default:
            LOG.info("Unknown TaskState="+state);
        }
        return null;
    }

    // We add the taskId on the end to force sort order:
    private static String toEid(Task task) {
        if ( null == task.entityId ) return null;
        return task.entityId + "@" + longToSortKey(task.taskId);
    }

    private static void fromEid(Task task, String eid) {
        if ( null == eid || eid.length() < LONG_SORT_KEY_LENGTH ) return;
        task.entityId = eid.substring(0, eid.length()-LONG_SORT_KEY_LENGTH-1);
    }

    private static String toNtty(Task task) {
        if ( null != task.taskState && task.taskState.isTerminal() ) {
            // terminal state:
            return null;
        }
        return task.entityType;
    }

    private static String toNtid(Task task) {
        if ( null != task.taskState && task.taskState.isTerminal() ) {
            // terminal state:
            return null;
        }
        return toEid(task);
    }

    private static long milliTime() {
        return TimeUnit.MILLISECONDS.convert(System.nanoTime(), TimeUnit.NANOSECONDS);
    }

    private void runNextTask() {
        // We are at capacity, don't actually run a task:
        if ( ! _capacity.tryAcquire(1) ) {
            // This is pretty common place, so do not log it:
            //LOG.warn("Insufficient capacity to run the next task, try increasing thread pool size");
            return;
        }
        Long taskId = null;
        try {
            // Spread-out running tasks to avoid slamming the DB with writes:
            synchronized ( this ) {
                long now = milliTime();
                long remaining = _lastRunTask + POLL_INTERVAL_MS/MAX_TASKS_IN_INTERVAL - now;
                if ( remaining > 0 ) {
                    // Delay the next task run:
                    if ( null == _runNextTaskFuture ) {
                        _runNextTaskFuture = schedule(this::runNextTask, remaining);
                    }
                    return;
                } else {
                    _runNextTaskFuture = null;
                    _lastRunTask = now;
                }
            }

            // Read tasks from the queue until we find one that is in a queued state and
            // therefore is likely to acquire the lock:
            while ( true ) {
                synchronized ( _taskQueue ) {
                    if ( _taskQueue.isEmpty() ) return;
                    taskId = _taskQueue.iterator().next();
                    _taskQueue.remove(taskId);
                }

                Task task = _tasks.getItem(taskId);
                if ( null != task && MONITOR_ID_QUEUED.equals(task.getMonitorId()) ) {
                    break;
                }
            }
            LOG.debug("runTask("+taskId+")");

            runTask(taskId);
        } catch ( Throwable ex ) {
            LOG.error("runTask("+taskId+"): "+ex.getMessage(), ex);
        } finally {
            _capacity.release();

            synchronized ( _taskQueue ) {
                if ( _taskQueue.isEmpty() ) return;
            }

            // It doesn't hurt to run this to much, but it does hurt to not
            // run it enough:
            submit(this::runNextTask);
        }
    }

    private synchronized void submit(Runnable run) {
        if ( null == _monitorTasks ) return;
        if ( null == _executor ) return;
        AtomicReference<Future<?>> futureRef = new AtomicReference<>();
        futureRef.set(_executor.submit(() -> {
                    try {
                        run.run();
                    } catch ( Throwable ex ) {
                        LOG.error(ex.getMessage(), ex);
                    } finally {
                        synchronized ( this ) {
                            _spawnedFutures.remove(futureRef.get());
                        }
                    }
                }));
        _spawnedFutures.add(futureRef.get());
    }

    private synchronized Future<?> schedule(Runnable run, long intervalMS) {
        if ( null == _monitorTasks ) return null;
        if ( null == _executor ) return null;
        AtomicReference<Future<?>> futureRef = new AtomicReference<>();
        futureRef.set(_executor.schedule(() -> {
                    try {
                        run.run();
                    } catch ( Throwable ex ) {
                        LOG.error(ex.getMessage(), ex);
                    } finally {
                        synchronized ( this ) {
                            _spawnedFutures.remove(futureRef.get());
                        }
                    }
                }, intervalMS, TimeUnit.MILLISECONDS));
        _spawnedFutures.add(futureRef.get());
        return futureRef.get();
    }

    private void submitRunTask(long taskId) {
        synchronized ( _taskQueue ) {
            _taskQueue.add(taskId);
        }
    }

    private synchronized void scheduleDelayedTask(long taskId, long intervalMS) {
        schedule(() ->
                 _monitor.monitor((mon) -> updateDelayedTask(taskId, mon)),
                 intervalMS);
    }

    private void monitorDelayedTask(Task task) {
        long taskId = task.getTaskId();
        DelayedTask delayedTask = new DelayedTask(task.getMillisecondsRemaining());
        synchronized ( delayedTask ) {
            if ( null != _delayedTasks.putIfAbsent(taskId, delayedTask) ) {
                LOG.debug("Already monitoring delayed taskId="+taskId);
                return;
            }
            long interval = Math.min(POLL_INTERVAL_MS, delayedTask.millisRemaining)
                - (milliTime() - delayedTask.millisTimeBegin);
            scheduleDelayedTask(taskId, interval);
            LOG.debug("Monitoring delayed taskId="+taskId);
        }
    }

    private void startRunnableTasks() {
        try {
            boolean taskSubmitted = false;
            for ( PageIterator iter : new PageIterator().pageSize(100) ) {
                for ( Task task : _tasksForMonitor.queryItems(MONITOR_ID_QUEUED, iter).list() ) {
                    if ( ! _taskMatches.test(task) ) continue;
                    submitRunTask(task.getTaskId());
                    taskSubmitted = true;
                }
            }
            if ( taskSubmitted ) {
                submit(this::runNextTask);
            }

            if ( 1 == _pollCount.incrementAndGet() % CLEANUP_INTERVALS ) {
                doCleanup();
            }

            // TODO: Occassionally find all MONITOR_ID_WAITING tasks to check if
            // cleanup needs to happen?

            // TODO: Poll for canceled tasks that we are running and interrupt them...
        } catch ( Throwable ex ) {
            LOG.error(ex.getMessage(), ex);
        }
    }

    private void doCleanup() {
        _monitor.monitor(this::doCleanup);
    }
    private void doCleanup(MonitorInfo monitorInfo) {
        // Delete all lid=... tid=TASK_ID_NONE, rtid=terminalStateTaskId,
        // mid=... entries (this "force" breaks locks):
        Map<String, Boolean> lockedIds = new HashMap<>();
        Map<Long, Boolean> taskInTerminalState = new HashMap<>();
        for ( PageIterator it : new PageIterator().pageSize(100) ) {
            for ( Lock lock :  _locksForMonitor.scanItems(it) ) {
                if ( null == lock.runningTaskId ) continue;
                if ( ! TASK_ID_NONE.equals(lock.taskId) ) continue;
                if ( Boolean.FALSE == taskInTerminalState.computeIfAbsent(
                         lock.runningTaskId, this::isTerminalTaskId) )
                {
                    lockedIds.put(lock.lockId, Boolean.TRUE);
                    continue;
                }
                // Simply delete the lock. Note that this will cause
                // waiting tasks to never run, however our next cleanup
                // task is to find tasks that are waiting and
                // clean them up, so we should be "okay".
                try {
                    _locks.deleteItem(
                        lock.lockId,
                        lock.taskId,
                        (expr) -> expr.and(
                            expr.eq("mid", lock.monitorId),
                            expr.and(
                                expr.eq("rtid", lock.runningTaskId),
                                expr.eq("agn", lock.tasksQueued))));
                    lockedIds.put(lock.lockId, Boolean.FALSE);
                    LOG.error("Found lockId="+lock.lockId+" was NOT removed, even though taskId="+
                              lock.runningTaskId+" is in a terminal state!");
                } catch ( RollbackException ex ) {
                    lockedIds.put(lock.lockId, Boolean.TRUE);
                    LOG.debug("Found lockId="+lock.lockId+" was already updated");
                }
            }
        }

        // Find tasks with mid=MONITOR_ID_WAITING and see if they should be MONITOR_ID_QUEUED.
        for ( PageIterator it : new PageIterator().pageSize(100) ) {
            for ( Task task : _tasksForMonitor.queryItems(MONITOR_ID_WAITING, it).list(
                      Arrays.asList(
                          "id", "any", "preq", "lids", "stat")) )
            {
                boolean anyPrereq = task.isAnyPrerequisiteTaskId();
                boolean unblock = ( anyPrereq ) ? false : true;
                for ( Long prereqTaskId : task.getPrerequisiteTaskIds() ) {
                    Boolean isTerminal = taskInTerminalState.computeIfAbsent(
                        prereqTaskId, this::isTerminalTaskId);
                    if ( anyPrereq ) {
                        if ( Boolean.TRUE == isTerminal ) {
                            unblock = true;
                            break;
                        }
                    } else if ( Boolean.FALSE == isTerminal ) {
                        unblock = false;
                        break;
                    }
                }
                // Still waiting for prerequisites:
                if ( ! unblock ) continue;
                // Check locks are free:
                for ( String lockId : task.getLockIds() ) {
                    if ( Boolean.TRUE == lockedIds.computeIfAbsent(
                             lockId, this::isLocked) )
                    {
                        unblock = false;
                        break;
                    }
                }
                // Still waiting to obtain lock:
                if ( ! unblock ) continue;
                // set mid="#"!
                try {
                    _tasks.updateItem(task.getTaskId(), null)
                        .set("mid", "#")
                        .when((expr) -> expr.eq("mid", "$"));
                    LOG.error(
                        "Found taskId="+task.getTaskId()+
                        " was NOT enqueued, even though all prerequisite tasks ("+
                        task.getPrerequisiteTaskIds()+") are satisfied and all locks ("+
                        task.getLockIds()+") are available!");
                } catch ( RollbackException ex ) {
                    LOG.debug("Found taskId="+task.getTaskId()+" was already enqueued");
                }
            }
        }
    }

    private boolean isTerminalTaskId(long taskId) {
        return getTaskState(
            _tasks.getItem(
                taskId,
                null,
                Arrays.asList("stat"),
                Task.class))
            .isTerminal();
    }

    private Boolean isLocked(String lockId) {
        Lock lock = _locks.getItem(
            lockId,
            TASK_ID_NONE,
            Arrays.asList("lid"),
            Lock.class);
        return lock != null;
    }

    private void updateDelayedTask(final long taskId, MonitorInfo monitorInfo) {
        DelayedTask delayedTask = _delayedTasks.get(taskId);
        if ( null == delayedTask ) return;
        synchronized ( delayedTask ) {
            UpdateItemBuilder update = _tasks.updateItem(taskId, null);
            long now = milliTime();
            long newRemaining = delayedTask.millisRemaining - (now - delayedTask.millisTimeBegin);
            if ( newRemaining <= 0 || null == monitorInfo ) {
                update.remove("tic")
                    .set("mid", AttrType.STR, MONITOR_ID_QUEUED)
                    .set("stat", AttrType.STR, toString(TaskState.QUEUED));
            } else {
                update.set("tic", AttrType.NUM, newRemaining)
                    .set("mid", AttrType.STR, monitorInfo.getMonitorId());
            }
            try {
                update.when((expr) -> expr.eq("tic", delayedTask.millisRemaining));
            } catch ( RollbackException ex ) {
                LOG.debug("Failed to update taskId="+taskId+" due to tic != "+delayedTask.millisRemaining);
                monitorInfo = null;
            }
            if ( newRemaining <= 0 || null == monitorInfo ) { // stop monitoring:
                _delayedTasks.remove(taskId, delayedTask);
                submitRunTask(taskId);
                submit(this::runNextTask);
                return;
            }
            delayedTask.millisRemaining = newRemaining;
            delayedTask.millisTimeBegin = now;
            long interval = Math.min(POLL_INTERVAL_MS, delayedTask.millisRemaining)
                - (milliTime() - now);
            scheduleDelayedTask(taskId, interval);
        }
    }

    private void runTask(long taskId) {
        try {
            _monitor.monitor((monitorInfo) -> lockAndRunTask(taskId, monitorInfo));
        } catch ( ShuttingDownException ex ) {
            synchronized ( this ) {
                _executor = null;
                LOG.error(ex.getMessage(), ex);
            }
        } catch ( Throwable ex ) {
            LOG.error("runTask("+taskId+") FAILED: "+ex.getMessage(), ex);
        }
    }

    private String getLockForTaskId(long taskId) {
        return "_TASK:"+longToSortKey(taskId);
    }

    private TaskState getTaskState(Task task) {
        if ( null == task ) return TaskState.FAILED;
        TaskState taskState = task.getTaskState();
        if ( null == taskState ) {
            LOG.error("Unexpected taskState=null for taskId="+task.getTaskId());
            return TaskState.FAILED;
        }
        return taskState;
    }

    private boolean checkPrerequisites(Task task) {
        boolean anyPrerequisiteComplete = false;
        List<Long> incompletePrerequisites = ( task.isAnyPrerequisiteTaskId() ) ?
            new ArrayList<>() : null;
        // First check the prerequisites:
        for ( Long prerequisiteId : task.getPrerequisiteTaskIds() ) {
            if ( null == prerequisiteId ) continue;
            String lockId = getLockForTaskId(prerequisiteId);
            String taskIdSortKey = longToSortKey(task.getTaskId());
            if ( anyPrerequisiteComplete ) {
                // Avoid cruft buildup:
                _locks.deleteItem(lockId, taskIdSortKey);
                continue;
            }
            TaskState state = getTaskState(_tasks.getItem(prerequisiteId));
            if ( state.isTerminal() ) {
                // Task completed, avoid leaving cruft:
                _locks.deleteItem(lockId, taskIdSortKey);
                if ( null != incompletePrerequisites ) {
                    anyPrerequisiteComplete = true;
                }
                continue;
            }
            // Enqueue:
            _locks.putItem(new Lock(lockId, taskIdSortKey));
            LOG.debug("enqueue prerequisite="+prerequisiteId+" for taskId="+task.getTaskId());
            try {
                // Force unblockWaitingTasks() to see our entry:
                _locks.updateItem(lockId, TASK_ID_NONE)
                    .increment("agn", 1)
                    .when((expr) -> expr.exists("mid"));
            } catch ( RollbackException ex ) {
                LOG.debug("Unable to increment agn field of lockId="+lockId+", checking if task is now terminal");
                // TODO: Make this a consistent read!
                state = getTaskState(_tasks.getItem(prerequisiteId));
                if ( state.isTerminal() ) {
                    // Task completed (and removed the lockId, TASK_ID_NONE field):
                    _locks.deleteItem(lockId, taskIdSortKey);
                    if ( null != incompletePrerequisites ) {
                        anyPrerequisiteComplete = true;
                    }
                    continue;
                }
                // Task isn't running, we have already enqueued, so we are good.
            }
            if ( null != incompletePrerequisites ) {
                incompletePrerequisites.add(prerequisiteId);
            } else {
                LOG.debug("Waiting on prerequisiteTaskId="+prerequisiteId+" for taskId="+task.getTaskId());
                task.taskState = TaskState.WAITING_FOR_PREREQUISITE;
                return false;
            }
        }
        if ( null != incompletePrerequisites ) {
            if ( ! anyPrerequisiteComplete ) {
                LOG.debug("Waiting on one of the prerequisiteTaskIds="+incompletePrerequisites+
                          " for taskId="+task.getTaskId());
                task.taskState = TaskState.WAITING_FOR_PREREQUISITE;
                return false;
            }
            // Avoid cruft buildup:
            for ( Long prerequisiteId : incompletePrerequisites ) {
                String lockId = getLockForTaskId(prerequisiteId);
                String taskIdSortKey = longToSortKey(task.getTaskId());
                _locks.deleteItem(lockId, taskIdSortKey);
            }
        }
        return true;
    }

    private boolean acquireLocks(Task task, List<String> locksAcquired, String monitorId)
        throws InterruptedException
    {
        List<String> lockIds = new ArrayList<>(task.getLockIds());
        lockIds.add(getLockForTaskId(task.getTaskId()));
        Collections.sort(lockIds);
      NEXT_LOCK:
        for ( String lockId : lockIds ) {
          RETRY:
            for ( int retry=0;; retry++ ) {
                // If we retry, do a random sleep:
                if ( retry > 0 ) Thread.sleep(ThreadLocalRandom.current().nextLong(500));

                // Try to acquire the lock:
                try {
                    Lock lock = _locks.updateItem(lockId, TASK_ID_NONE)
                        .set("mid", AttrType.STR, monitorId)
                        .set("rtid", AttrType.NUM, task.getTaskId())
                        .increment("agn", 1)
                        .returnAllNew()
                        .when((expr) -> expr.or(
                                  expr.eq("rtid", task.getTaskId()),
                                  expr.not(expr.exists("mid"))));
                    locksAcquired.add(lockId);
                } catch ( RollbackException ex1 ) {
                    LOG.debug("Unable to acquire lockId="+lockId+" for taskId="+task.getTaskId());

                    // Enqueue:
                    String taskIdStr = longToSortKey(task.getTaskId());
                    _locks.putItem(new Lock(lockId, taskIdStr));

                    // Force unblockWaitingTasks() to see our entry:
                    try {
                        _locks.updateItem(lockId, TASK_ID_NONE)
                            .increment("agn", 1)
                            .when((expr) -> expr.exists("mid"));
                    } catch ( RollbackException ex2 ) {
                        LOG.debug("Unable to increment agn field of lockId="+lockId+", retrying");
                        continue RETRY;
                    }

                    task.taskState = TaskState.WAITING_FOR_LOCK;
                    return false;
                }
                continue NEXT_LOCK;
            }
        }
        return true;
    }

    private class TaskContextImpl implements TaskContext {
        private Task _task;
        private MonitorInfo _monitorInfo;
        private TaskContextImpl(Task task, MonitorInfo monitorInfo) {
            _task = task;
            _monitorInfo = monitorInfo;
        }
        @Override
        public TaskInfo getTaskInfo() {
            return _task;
        }
        @Override
        public MonitorInfo getMonitorInfo() {
            return _monitorInfo;
        }
        @Override
        public byte[] getUpdateData() {
            return (null == _task) ? null : _task.updateData;
        }
        @Override
        public void commitCheckpointData(byte[] checkpointData) {
            try {
                _tasks.updateItem(_task.getTaskId(), null)
                    .set("st8", AttrType.BIN, checkpointData)
                    .when((expr) -> expr.eq("mid", _monitorInfo.getMonitorId()));
            } catch ( RollbackException ex ) {
                throw new LostLockException("taskId="+_task.getTaskId());
            }
        }
    }

    private String getThreadName(TaskInfo task) {
        return String.format("TASK:0x%x", task.getTaskId());
    }

    private void lockAndRunTask(long taskId, MonitorInfo monitorInfo) {
        // Lock the task:
        Task originalTask;
        List<String> locksAcquired = new ArrayList<>();
        String threadName = null;
        try {
            originalTask = _tasks.updateItem(taskId, null)
                .set("mid", AttrType.STR, monitorInfo.getMonitorId())
                .set("stat", AttrType.STR, toString(TaskState.RUNNING))
                .set("ts", AttrType.NUM, System.currentTimeMillis())
                .increment("cnt", 1)
                .returnAllNew()
                .when((expr) -> expr.eq("mid", MONITOR_ID_QUEUED));
        } catch ( RollbackException ex ) {
            // Someone else already locked this task:
            LOG.debug("Something else is running taskId="+taskId);
            return;
        }
        boolean submitQueuedTask = true;
        boolean interrupted = false;
        Task finalTask = new Task(originalTask);
        finalTask.taskState = TaskState.QUEUED;
        try {
            threadName = Thread.currentThread().getName();
            Thread.currentThread().setName(getThreadName(finalTask));
            if ( null != finalTask.getCanceledBy() ) {
                finalTask.taskState = TaskState.CANCELED;
                return;
            }
            if ( null == originalTask.updateData ) {
                if ( null != finalTask.getMillisecondsRemaining() ) {
                    monitorDelayedTask(finalTask);
                    // NOTE: we MUST keep the mid locked in this scenario!
                    finalTask.taskState = TaskState.WAITING_FOR_INTERVAL;
                    return;
                }

                if ( ! checkPrerequisites(finalTask) ) return;
                if ( ! acquireLocks(finalTask, locksAcquired, monitorInfo.getMonitorId()) ) {
                    return;
                }
            }
            TaskFunction taskFunction = _taskFunctions.get(finalTask.getEntityType());
            if ( null == taskFunction ) {
                LOG.info("Unsupported entityType="+finalTask.getEntityType()+" taskId="+taskId);
                // Wait for a time period:
                finalTask.millisecondsRemaining = 60000L;
                finalTask.taskState = TaskState.SUCCESS;
                submitQueuedTask = false;
                return;
            }

            Throwable err = null;
            try {
                LOG.debug("Running taskId="+taskId+" entityType="+finalTask.getEntityType()+" entityId="+finalTask.getEntityId());
                TaskInfo taskInfo = taskFunction.run(new TaskContextImpl(originalTask, monitorInfo));
                if ( null != taskInfo ) {
                    finalTask = new Task(taskInfo);
                }
                finalTask.taskState = TaskState.SUCCESS;
            } catch ( Throwable ex ) {
                if ( ! Thread.interrupted() && ! isInterruptedException(ex) ) {
                    finalTask.errorId = CompactUUID.randomUUID().toString();
                    LOG.debug("Failed taskId="+taskId+" errorId="+finalTask.errorId+": "+ex.getMessage(), ex);

                    StringWriter stackTrace = new StringWriter();
                    stackTrace.append("on nodeName="+monitorInfo.getNodeName()+" ");
                    ex.printStackTrace(new PrintWriter(stackTrace));
                    finalTask.errorMessage = ex.getMessage();
                    finalTask.errorMessageStackTrace = stackTrace.toString();
                    finalTask.taskState = TaskState.FAILED;
                } else {
                    interrupted = true;
                    LOG.debug("Ignoring interrupted thread exception "+ex.getMessage(), ex);
                    finalTask.taskState = TaskState.QUEUED;
                    return;
                }
            }
        } catch ( LostLockException|InterruptedException ex ) {
            LOG.error("Failing heartbeat "+monitorInfo.getMonitorId()+" due to taskId="+
                      taskId+": "+ex.getMessage(), ex);
            monitorInfo.forceHeartbeatFailure();
        } finally {
            try {
                if ( monitorInfo.hasFailedHeartbeat() ) {
                    if ( interrupted ) Thread.currentThread().interrupt();
                    return;
                }
                try {
                    interrupted |= updateTaskState(
                        originalTask, finalTask, locksAcquired, monitorInfo, submitQueuedTask);
                } catch ( Throwable ex ) {
                    LOG.error("Failing heartbeat "+monitorInfo.getMonitorId()+
                              " in updateTaskState due to taskId="+taskId+": "+
                              ex.getMessage(), ex);
                    monitorInfo.forceHeartbeatFailure();
                }
                if ( interrupted ) {
                    Thread.currentThread().interrupt();
                }
                if ( finalTask.getTaskState().isTerminal() ) {
                    onTerminalState(finalTask);
                }
            } finally {
                if ( null != threadName ) {
                    Thread.currentThread().setName(threadName);
                }
            }
        }
    }

    // Returns true if thread was interrupted...
    private boolean updateTaskState(Task originalTask, Task finalTask, List<String> locksAcquired, MonitorInfo monitorInfo, boolean submitQueuedTask) {
        boolean interrupted = false;
        long taskId = originalTask.getTaskId();
        List<Long> taskIdsToRun = new ArrayList<>();
        for ( int retry=0; retry < 3; retry++ ) {
            try {
                // First update all DB state:
                UpdateItemBuilder update = buildUpdateTaskState(originalTask, finalTask);

                // We MUST commit any terminal states of the task BEFORE the
                // prereqs lock is processed, otherwise this log line:
                // "Unable to increment agn field of lockId="... in
                // acquireLocks() will do a double-check of the task state only
                // to find the task still is not in a terminal state and
                // therefore the code will assume the enqueue of the prereq was
                // successful.

                boolean checkForRequeue = MONITOR_ID_WAITING.equals(finalTask.monitorId);
                try {
                    update.when((expr) -> {
                            FilterCondExpr result = expr.eq("mid", monitorInfo.getMonitorId());
                            if ( checkForRequeue ) {
                                result = expr.and(result,
                                                  ( null == originalTask.requeues ) ?
                                                  expr.not(expr.exists("agn")) :
                                                  expr.eq("agn", originalTask.requeues));
                            }
                            return result;
                        });
                } catch ( RollbackException rollbackEx ) {
                    if ( checkForRequeue ) {
                        Task task = _tasks.getItem(taskId);
                        if ( null != task && monitorInfo.getMonitorId().equals(task.getMonitorId()) ) {
                            LOG.debug("'agn'="+originalTask.requeues+" of taskId="+taskId+
                                      " changed to="+task.requeues+" during run, retrying");
                            finalTask.taskState = TaskState.QUEUED;
                            if ( submitQueuedTask ) taskIdsToRun.add(taskId);
                            continue;
                        }
                    }
                    throw new LostLockException("taskId="+taskId);
                }
                if ( null != originalTask.updateData ) {
                    // Remove the update data, but only if has remained unchanged (which
                    // is why we have to do a separate update):
                    try {
                        UpdateItemBuilder removeUpd = _tasks.updateItem(originalTask.getTaskId(), null)
                            .remove("upd");
                        if ( finalTask.getTaskState().isTerminal() ) {
                            removeUpd.always();
                        } else {
                            removeUpd.when((expr) -> expr.eq("upd", originalTask.updateData));
                        }
                    } catch ( RollbackException ex ) {
                        LOG.debug("'upd' of taskId="+taskId+" changed during run, not clearing 'upd'");
                    }
                }

                releaseLocks(locksAcquired,
                             taskId,
                             monitorInfo.getMonitorId(),
                             taskIdsToRun,
                             finalTask.getTaskState().isTerminal());

                // Get the tasks to run immediately:
                if ( _monitor.isActiveMonitor(monitorInfo) ) {
                    boolean taskSubmitted = false;
                    for ( Long taskIdToRun : taskIdsToRun ) {
                        submitRunTask(taskIdToRun);
                        taskSubmitted = true;
                    }
                    taskIdsToRun.clear();

                    if ( submitQueuedTask && TaskState.QUEUED == finalTask.getTaskState() ) {
                        submitRunTask(taskId);
                        taskSubmitted = true;
                    }
                    if ( taskSubmitted ) {
                        submit(this::runNextTask);
                    }
                }
                return interrupted;
            } catch ( RuntimeException|InterruptedException ex ) {
                if ( Thread.interrupted() || isInterruptedException(ex) ) {
                    interrupted = true;
                    LOG.debug("Interrupted in attempt to updateTaskState("+taskId+"): "+ex.getMessage(), ex);
                    continue;
                }
                throw (RuntimeException)ex;
            }
        }
        monitorInfo.forceHeartbeatFailure();
        LOG.error("Interrupted to many times, giving up on updateTaskState("+taskId+"), failing the monitor!");
        return interrupted;
    }

    private UpdateItemBuilder buildUpdateTaskState(Task originalTask, Task finalTask) {
        UpdateItemBuilder update = _tasks.updateItem(originalTask.getTaskId(), null);
        finalTask.monitorId = null;
        StringBuilder logMsg = new StringBuilder();
        switch ( finalTask.getTaskState() ) {
        case WAITING_FOR_INTERVAL:
            // Special case since we keep the mid locked:
            update.set("stat", AttrType.STR, toString(TaskState.WAITING_FOR_INTERVAL));
            LOG.debug("taskId="+originalTask.getTaskId()+" state="+finalTask.getTaskState());
            return update;
        case FAILED:
            update.set("err", AttrType.STR, finalTask.getErrorMessage())
                .set("errId", AttrType.STR, finalTask.getErrorId())
                .set("errT", AttrType.STR, finalTask.getErrorStackTrace());
            logMsg.append(" update err, errId, errT");
            break;
        case SUCCESS:
            if ( ! Arrays.equals(originalTask.getCheckpointData(), finalTask.getCheckpointData()) ) {
                update.set("st8", AttrType.BIN, finalTask.getCheckpointData());
                logMsg.append(" update st8");
            }
            if ( ! originalTask.getLockIds().equals(finalTask.getLockIds()) ) {
                finalTask.monitorId = MONITOR_ID_QUEUED;
                finalTask.taskState = TaskState.QUEUED;
                if ( finalTask.getLockIds().isEmpty() ) {
                    update.remove("lids");
                    logMsg.append(" delete lids");
                } else {
                    update.set("lids", AttrType.STR_SET, finalTask.getLockIds());
                    logMsg.append(" set lids="+finalTask.getLockIds());
                }
            }
            if ( ! originalTask.getPrerequisiteTaskIds().equals(finalTask.getPrerequisiteTaskIds()) ) {
                finalTask.monitorId = MONITOR_ID_QUEUED;
                finalTask.taskState = TaskState.QUEUED;
                if ( finalTask.getPrerequisiteTaskIds().isEmpty() ) {
                    update.remove("preq")
                        .remove("any");
                    logMsg.append(" remove preq");
                } else {
                    update.set("preq", AttrType.STR_SET, finalTask.getPrerequisiteTaskIds())
                        .set("any", AttrType.BOOL, finalTask.isAnyPrerequisiteTaskId());
                    logMsg.append(" set preq="+finalTask.getPrerequisiteTaskIds());
                }
            }
            if ( null != finalTask.getMillisecondsRemaining() ) {
                finalTask.monitorId = MONITOR_ID_QUEUED;
                finalTask.taskState = TaskState.QUEUED;
                update.set("tic", AttrType.NUM, finalTask.getMillisecondsRemaining());
                logMsg.append(" set tic="+finalTask.getMillisecondsRemaining());
            }
            break;
        case WAITING_FOR_PREREQUISITE:
        case WAITING_FOR_LOCK:
            finalTask.monitorId = MONITOR_ID_WAITING;
            break;
        case QUEUED:
            finalTask.monitorId = MONITOR_ID_QUEUED;
            break;
        case RUNNING:
            throw new IllegalStateException("finalTask should NEVER be marked as RUNNING!");
        case CANCELED:
            // release the monitor and set the task state as such...
            break;
        }
        update.set("stat", AttrType.STR, toString(finalTask.getTaskState()))
            .set("tf", AttrType.NUM, System.currentTimeMillis());
        if ( null == finalTask.monitorId ) {
            logMsg.append(" delete mid, ntty, ntid");
            update.remove("mid")
                .remove("ntty")
                .remove("ntid");
        } else {
            logMsg.append(" set mid="+finalTask.monitorId);
            update.set("mid", AttrType.STR, finalTask.monitorId);
        }
        LOG.debug(
            "taskId="+originalTask.getTaskId()+" state="+finalTask.getTaskState()+logMsg.toString());
        return update;
    }

    private boolean isInterruptedException(Throwable ex) {
        switch ( ex.getClass().getName() ) {
        case "com.amazonaws.AbortedException":
        case "java.lang.InterruptedException":
            return true;
        }
        return false;
    }

    private void releaseLocks(List<String> locks, Long taskId, String monitorId, List<Long> taskIdsToRun, boolean isTerminal)
        throws InterruptedException
    {
        String prereqsLock = getLockForTaskId(taskId);
        while ( ! locks.isEmpty() ) {
            String lockId = locks.get(locks.size()-1);

            // Mark next task as runnable:
            unblockWaitingTasks(lockId, monitorId, taskIdsToRun,
                                isTerminal && lockId.equals(prereqsLock));
            // Remove our queued mark:
            if ( null != taskId ) {
                _locks.deleteItem(lockId, longToSortKey(taskId));
            }
            locks.remove(locks.size()-1);
        }
    }

    private void unblockWaitingTasks(String lockId, String monitorId, List<Long> taskIdsToRun, boolean processPrereqs)
        throws InterruptedException
    {
        if ( lockId.startsWith("_TASK:") ) {
            if ( ! processPrereqs ) {
                // We still need to release the lock:
                try {
                    _locks.deleteItem(lockId, TASK_ID_NONE, (expr) -> expr.eq("mid", monitorId));
                    LOG.debug("released lockId="+lockId);
                } catch ( RollbackException ex ) {
                    throw new LostLockException("lockId="+lockId);
                }
                return;
            }
            LOG.debug("unblocking all prerequisites lockId="+lockId);
        } else {
            processPrereqs = false;
        }
        for ( int retry=0;; retry++ ) {
            // If we retry, do a random sleep:
            if ( retry > 0 ) Thread.sleep(ThreadLocalRandom.current().nextLong(500));
            Long tasksQueued = null;
            for ( PageIterator iter : new PageIterator().pageSize(processPrereqs ? 100 : 2) ) {
              NEXT_LOCK:
                for ( Lock lock : _locks.queryItems(lockId, iter).list() ) {
                    if ( TASK_ID_NONE.equals(lock.taskId) ) {
                        tasksQueued = lock.tasksQueued;
                        continue;
                    }
                    Long taskId = sortKeyToLong(lock.taskId);
                    boolean first = true;
                    while ( true ) {
                        try {
                            _tasks.updateItem(taskId, null)
                                .set("mid", AttrType.STR, MONITOR_ID_QUEUED)
                                .set("stat", AttrType.STR, toString(TaskState.QUEUED))
                                .when((expr) -> expr.eq("mid", MONITOR_ID_WAITING));
                            LOG.debug("unblocked taskId="+taskId+" that was waiting for "+lockId);
                            taskIdsToRun.add(taskId);
                            break;
                        } catch ( RollbackException ex ) {
                            if ( first ) {
                                first = false;
                                LOG.debug("taskId="+taskId+" was not in a waiting state, incrementing 'agn'");
                                _tasks.updateItem(taskId, null)
                                    .increment("agn", 1)
                                    .always();
                            } else {
                                LOG.debug("taskId="+taskId+" was not in a waiting state");
                                continue NEXT_LOCK;
                            }
                        }
                    }
                    // Just unblocking a single task
                    if ( ! processPrereqs ) break;
                }
            }
            if ( null == tasksQueued ) {
                LOG.error("Expected lockId="+lockId+" to exist!");
                return;
            }
            try {
                Long finalTasksQueued = tasksQueued;
                _locks.deleteItem(lockId, TASK_ID_NONE, (expr) -> expr.and(
                                      expr.eq("mid", monitorId),
                                      expr.eq("agn", finalTasksQueued)));
                LOG.debug("released lockId="+lockId);
            } catch ( RollbackException ex ) {
                Lock lock = _locks.getItem(lockId, TASK_ID_NONE);
                if ( null == lock || ! monitorId.equals(lock.monitorId) ) {
                    throw new LostLockException("lockId="+lockId);
                }
                LOG.debug("Retrying unblockWaitingTask("+lockId+")");
                continue;
            }
            return;
        }
    }
}
