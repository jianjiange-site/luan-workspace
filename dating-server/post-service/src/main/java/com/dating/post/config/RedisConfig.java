package com.dating.post.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis configuration. All keys are prefixed with the luan: isolation prefix
 * configured via app.cache.key-prefix.
 */
@Configuration
public class RedisConfig {

    @Value("${app.cache.key-prefix:luan}")
    private String keyPrefix;

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(StringRedisSerializer.UTF_8);
        template.setValueSerializer(StringRedisSerializer.UTF_8);
        template.setHashKeySerializer(StringRedisSerializer.UTF_8);
        template.setHashValueSerializer(StringRedisSerializer.UTF_8);
        return template;
    }

    /**
     * Returns the configured key prefix (e.g. "luan") for use in CacheKeyBuilder.
     */
    public String getKeyPrefix() {
        return keyPrefix;
    }
}
