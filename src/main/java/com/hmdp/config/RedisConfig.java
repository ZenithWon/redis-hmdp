package com.hmdp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

@Configuration
public class RedisConfig {
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(redisConnectionFactory);
        // 使用 GenericJackson2JsonRedisSerializer 替换默认序列化
        GenericJackson2JsonRedisSerializer genericFastJsonRedisSerializer=new GenericJackson2JsonRedisSerializer();
        // 设置key和value的序列化规则
        redisTemplate.setKeySerializer(RedisSerializer.string());
        redisTemplate.setValueSerializer(genericFastJsonRedisSerializer);
        // 设置hashKey和hashValue的序列化规则
        redisTemplate.setHashKeySerializer(RedisSerializer.string());
        redisTemplate.setHashValueSerializer(genericFastJsonRedisSerializer);
        return redisTemplate;
    }
}
