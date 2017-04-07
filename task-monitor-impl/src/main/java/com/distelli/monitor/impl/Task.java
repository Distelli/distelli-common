package com.distelli.monitor.impl;

import java.util.Collections;
import java.util.Set;
import java.util.Arrays;
import com.distelli.monitor.TaskState;
import com.distelli.monitor.TaskInfo;
import com.distelli.monitor.TaskBuilder;
import static javax.xml.bind.DatatypeConverter.printBase64Binary;

public class Task implements TaskInfo {
    public Long taskId;
    public String entityType;
    public String entityId;
    public TaskState taskState;
    public Set<String> lockIds;
    public Set<Long> prerequisiteTaskIds;
    public String monitorId;
    public byte[] checkpointData;
    public String errorMessage;
    public String errorId;
    public String errorMessageStackTrace;
    public Long startTime;
    public Long endTime;
    public Long runCount;
    public Long millisecondsRemaining;
    public String canceledBy;

    public Task() {}
    public Task(Task src) {
        taskId = src.taskId;
        entityType = src.entityType;
        entityId = src.entityId;
        taskState = src.taskState;
        lockIds = src.lockIds;
        prerequisiteTaskIds = src.prerequisiteTaskIds;
        monitorId = src.monitorId;
        checkpointData = src.checkpointData;
        errorMessage = src.errorMessage;
        errorId = src.errorId;
        errorMessageStackTrace = src.errorMessageStackTrace;
        startTime = src.startTime;
        endTime = src.endTime;
        runCount = src.runCount;
        millisecondsRemaining = src.millisecondsRemaining;
        canceledBy = src.canceledBy;
    }

    @Override
    public Long getTaskId() {
        return taskId;
    }

    @Override
    public String getEntityType() {
        return entityType;
    }

    @Override
    public String getEntityId() {
        return entityId;
    }

    @Override
    public TaskState getTaskState() {
        return taskState;
    }

    @Override
    public Set<String> getLockIds() {
        if ( null == lockIds ) return Collections.emptySet();
        return Collections.unmodifiableSet(lockIds);
    }

    @Override
    public Set<Long> getPrerequisiteTaskIds() {
        if ( null == prerequisiteTaskIds ) return Collections.emptySet();
        return Collections.unmodifiableSet(prerequisiteTaskIds);
    }

    @Override
    public String getMonitorId() {
        return monitorId;
    }

    @Override
    public byte[] getCheckpointData() {
        if ( null == checkpointData ) return null;
        return Arrays.copyOf(checkpointData, checkpointData.length);
    }

    @Override
    public String getErrorMessage() {
        return errorMessage;
    }

    @Override
    public String getErrorId() {
        return errorId;
    }

    @Override
    public String getErrorStackTrace() {
        return errorMessageStackTrace;
    }

    @Override
    public Long getStartTime() {
        return startTime;
    }

    @Override
    public Long getEndTime() {
        return endTime;
    }

    @Override
    public Long getRunCount() {
        return runCount;
    }

    @Override
    public Long getMillisecondsRemaining() {
        return millisecondsRemaining;
    }

    @Override
    public String getCanceledBy() {
        return canceledBy;
    }

    @Override
    public TaskBuilder toBuilder() {
        return new TaskBuilderImpl(new Task(this));
    }

    @Override
    public String toString() {
        return "TaskInfo:{"
            +"taskId="+taskId
            +",entityType="+entityType
            +",entityId="+entityId
            +",taskState="+taskState
            +",lockIds="+lockIds
            +",prerequisiteTaskIds="+prerequisiteTaskIds
            +",monitorId="+monitorId
            +",checkpointData="+printBase64Binary(null == checkpointData ? new byte[0] : checkpointData)
            +",errorMessage="+errorMessage
            +",errorId="+errorId
            +",errorMessageStackTrace="+errorMessageStackTrace
            +",startTime="+startTime
            +",endTime="+endTime
            +",runCount="+runCount
            +",millisecondsRemaining="+millisecondsRemaining
            +",canceledBy="+canceledBy
            +"}";
    }
}
