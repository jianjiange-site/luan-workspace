package com.dating.post.config;

import net.devh.boot.grpc.client.inject.GrpcClient;
import net.devh.boot.grpc.client.inject.GrpcClientBean;
import org.springframework.context.annotation.Configuration;

/**
 * gRPC client stubs injection point.
 * UserClient wires these stubs to call user-service via Nacos discovery.
 *
 * Note: actual stub class is com.dating.luan.proto.user.UserServiceGrpc.UserServiceBlockingStub
 * from user-proto package. Until user-service proto is published, UserClient uses stub implementations.
 */
@Configuration
public class GrpcClientConfig {
    // Stub injection happens in UserClient via @GrpcClient("user-service")
    // This config class exists as a placeholder for future stub wiring.
}
