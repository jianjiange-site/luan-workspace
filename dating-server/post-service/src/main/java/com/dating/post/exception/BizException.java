package com.dating.post.exception;

import lombok.Getter;

/**
 * Business exception with error code and message.
 * Caught by GlobalExceptionHandler and mapped to BaseResponse in gRPC responses.
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
