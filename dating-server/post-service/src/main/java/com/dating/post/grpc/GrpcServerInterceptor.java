package com.dating.post.grpc;

import io.grpc.*;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;

/**
 * Extracts x-user-id from gRPC Metadata on every incoming request.
 * <p>
 * mobile-gateway decodes the JWT and injects x-user-id into gRPC Metadata.
 * This interceptor reads it and sets it into RequestContext for downstream access.
 * <p>
 * The user_id is NOT placed in proto request fields (except for target-user params
 * like ListUserPosts.user_id which is a query parameter, not the authenticated user).
 */
@Slf4j
@GrpcGlobalServerInterceptor
public class GrpcServerInterceptor implements ServerInterceptor {

    private static final Metadata.Key<String> USER_ID_KEY =
            Metadata.Key.of("x-user-id", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        String userIdStr = headers.get(USER_ID_KEY);
        Long userId = null;
        if (userIdStr != null && !userIdStr.isEmpty()) {
            try {
                userId = Long.parseLong(userIdStr);
                RequestContext.setUserId(userId);
            } catch (NumberFormatException e) {
                log.warn("Invalid x-user-id in gRPC metadata: {}", userIdStr);
            }
        }

        // Wrapping the call to clean up RequestContext after completion
        ServerCall<ReqT, RespT> wrappedCall = new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
                RequestContext.clear();
                super.close(status, trailers);
            }
        };

        return next.startCall(wrappedCall, headers);
    }
}
