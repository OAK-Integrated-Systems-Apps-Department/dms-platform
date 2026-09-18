package com.oak.dms.metadata.cache;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Explicit JSON (de)serialization for the schema cache - never JDK
 * serialization, which would tie every cache entry to a fixed serialVersionUID
 * and break silently across deploys. {@link GenericJackson2JsonRedisSerializer}
 * embeds a {@code @class} type hint per value, so heterogeneous cache values
 * (a {@code List} of a private nested record type, in this module's case)
 * round-trip to their original type without the caller having to specify it.
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> schemaRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
