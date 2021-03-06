package com.distelli.webserver;

public class JsonError
{
    //Standard Errors
    public static JsonError MalformedRequest = new JsonError("The request is Malformed", Codes.MalformedRequest, 400);
    public static JsonError UnsupportedHttpMethod = new JsonError("The HttpMethod is unsupported", Codes.UnsupportedHttpMethod, 400);
    public static JsonError UnsupportedOperation = new JsonError("The Operation is unsupported", Codes.UnsupportedOperation, 400);
    public static JsonError InternalServerError = new JsonError("An Internal Server Error occurred", Codes.InternalServerError, 500);
    public static JsonError BadContentType = new JsonError("The content type is unsupported", Codes.BadContentType, 400);
    public static JsonError BadContent = new JsonError("The content field is missing or invalid", Codes.BadContent, 400);
    public static JsonError MissingParam = new JsonError("Missing param in request", Codes.MissingParam, 400);
    public static JsonError BadParam = new JsonError("Bad param in request", Codes.BadParam, 400);
    public static JsonError NotFound = new JsonError("The requested resource was not found", Codes.NotFound, 404);

    public static final class Codes {
        public static final String MalformedRequest = "MalformedRequest";
        public static final String UnsupportedHttpMethod = "UnsupportedHttpMethod";
        public static final String UnsupportedOperation = "UnsupportedOperation";
        public static final String InternalServerError = "InternalServerError";
        public static final String BadContent = "BadContent";
        public static final String BadContentType = "BadContentType";
        public static final String MissingParam = "MissingParam";
        public static final String BadParam = "BadParam";
        public static final String NotFound = "NotFound";
    }

    private static class Error {
        private String message;
        private String code;

        public Error(String message, String code) {
            this.message = message;
            this.code = code;
        }
        public String getMessage()
        {
            return this.message;
        }

        public String getCode()
        {
            return this.code;
        }
    }

    private Error error;
    private int httpStatusCode = -1;

    public JsonError()
    {

    }

    public JsonError(String message, String code, int httpStatusCode)
    {
        this.error = new Error(message, code);
        this.httpStatusCode = httpStatusCode;
    }

    public JsonError(String message, String code)
    {
        this.error = new Error(message, code);
    }

    public Error getError()
    {
        return this.error;
    }

    public String errorCode()
    {
        if(this.error == null)
            return null;
        return this.error.getCode();
    }

    public String errorMessage()
    {
        if(this.error == null)
            return null;
        return this.error.getMessage();
    }

    public int httpStatusCode()
    {
        return httpStatusCode;
    }
}
