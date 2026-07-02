package com.dating.match.exception;

import com.dating.luan.proto.match.BaseResponse;
import com.dating.match.constant.ErrorCode;
import lombok.extern.slf4j.Slf4j;

/**
 * Maps BizException to BaseResponse with gRPC status OK.
 * Business errors are communicated via BaseResponse.code, not gRPC status codes.
 */
@Slf4j
public final class GlobalExceptionHandler {

    private GlobalExceptionHandler() {}

    /**
     * Convert a BizException to a BaseResponse with the exception's code and message.
     */
    public static BaseResponse toBaseResponse(BizException e) {
        return BaseResponse.newBuilder()
                .setCode(e.getCode())
                .setMessage(e.getMessage() != null ? e.getMessage() : "")
                .build();
    }

    /**
     * Convert a generic Exception to a 5000 INTERNAL_ERROR BaseResponse.
     * Stack trace is logged server-side, never returned to the caller.
     */
    public static BaseResponse toBaseResponse(Exception e) {
        log.error("Unhandled exception", e);
        return BaseResponse.newBuilder()
                .setCode(ErrorCode.INTERNAL_ERROR)
                .setMessage("Internal server error")
                .build();
    }

    /**
     * Build a success BaseResponse.
     */
    public static BaseResponse success() {
        return BaseResponse.newBuilder()
                .setCode(ErrorCode.OK)
                .setMessage("ok")
                .build();
    }
}
