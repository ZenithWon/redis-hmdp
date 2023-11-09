package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final long BEGIN_TIMESTAMP=1672531200L;
    private static final int COUNT_BITS=32;

    public long netId(String keyPrefix){
        long now = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        long timestamp=now-BEGIN_TIMESTAMP;

        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + date);

        return (timestamp<<COUNT_BITS)|count;
    }
}
