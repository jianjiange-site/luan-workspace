package com.dating.match.grpc;

/**
 * Thread-local holder for the current request's authenticated user_id.
 * Set by GrpcServerInterceptor from gRPC Metadata (x-user-id header).
 * mobile-gateway injects this after JWT validation.
 */
public final class RequestContext {

    private static final ThreadLocal<Long> USER_ID_HOLDER = new ThreadLocal<>();

    private RequestContext() {}

    /**
     * Set the current user_id for this gRPC request thread.
     */
    public static void setUserId(Long userId) {
        USER_ID_HOLDER.set(userId);
    }

    /**
     * Get the current user_id, or null if not authenticated (e.g. anonymous read).
     */
    public static Long getUserId() {
        return USER_ID_HOLDER.get();
    }

    /**
     * Get the current user_id, throwing if not set.
     * Use for RPCs that require authentication.
     */
    public static Long requireUserId() {
        Long userId = USER_ID_HOLDER.get();
        if (userId == null) {
            throw new IllegalStateException("user_id not found in gRPC context — is mobile-gateway injecting x-user-id?");
        }
        return userId;
    }

    /**
     * Clear the thread-local (called by interceptor after RPC completes).
     */
    public static void clear() {
        USER_ID_HOLDER.remove();
    }
}
