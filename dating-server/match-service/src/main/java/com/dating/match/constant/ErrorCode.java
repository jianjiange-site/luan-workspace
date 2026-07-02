package com.dating.match.constant;

/**
 * Business error codes returned in BaseResponse.code.
 * gRPC status remains OK; clients inspect this field to determine success/failure.
 */
public final class ErrorCode {

    private ErrorCode() {}

    public static final int OK = 0;

    // 4xxx — client errors
    public static final int USER_ID_REQUIRED = 4001;
    public static final int TARGET_USER_ID_REQUIRED = 4002;
    public static final int INVALID_SWIPE_DIRECTION = 4003;
    public static final int PAGE_SIZE_EXCEEDED = 4004;

    // 41xx — quota errors
    public static final int QUOTA_RIGHT_SWIPE_EXCEEDED = 4101;
    public static final int QUOTA_CARDS_EXCEEDED = 4102;
    public static final int QUOTA_SUPER_HI_EXCEEDED = 4103;
    public static final int INSUFFICIENT_COINS = 4104;
    public static final int CONCURRENT_SWIPE = 4105;

    // 403x — permission
    public static final int PERMISSION_DENIED = 4030;

    // 5xxx — server errors
    public static final int INTERNAL_ERROR = 5000;
}
