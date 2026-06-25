package com.dating.post.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson client configuration for distributed locks and Bloom filters.
 * Uses the same Redis connection as Spring Data Redis.
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host:38.76.188.242}")
    private String host;

    @Value("${spring.data.redis.port:6380}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Value("${spring.data.redis.database:1}")
    private int database;

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        String address = "redis://" + host + ":" + port;
        config.useSingleServer()
                .setAddress(address)
                .setPassword(password.isEmpty() ? null : password)
                .setDatabase(database)
                .setConnectionPoolSize(16)
                .setConnectionMinimumIdleSize(4);
        return Redisson.create(config);
    }
}
