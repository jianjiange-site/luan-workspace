package com.dating.post.constant;

/**
 * Business error codes returned in BaseResponse.code.
 * gRPC status remains OK; clients inspect this field to determine success/failure.
 */
public final class ErrorCode {

    private ErrorCode() {}

    public static final int OK = 0;

    // 4xxx — client errors
    public static final int CONTENT_EMPTY = 4001;
    public static final int CONTENT_TOO_LONG = 4002;
    public static final int IMAGE_COUNT_EXCEEDED = 4003;
    public static final int IMAGE_KEY_EMPTY = 4004;
    public static final int POST_NOT_FOUND = 4005;
    public static final int COMMENT_NOT_FOUND = 4006;
    public static final int COMMENT_CONTENT_EMPTY = 4007;
    public static final int COMMENT_CONTENT_TOO_LONG = 4008;
    public static final int PERMISSION_DENIED = 4030;

    // 5xxx — server errors
    public static final int INTERNAL_ERROR = 5000;
}
