package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {
    private StringRedisTemplate stringRedisTemplate;
    private String name;

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + '-';


    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate , String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }


    @Override
    public boolean tryLock(long timeoutSec) {
        String threadId = ID_PREFIX + Thread.currentThread().getId();

        Boolean lock = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name , threadId , timeoutSec , TimeUnit.SECONDS);
        return BooleanUtil.isTrue(lock);
    }

    @Override
    public void unLock() {
        String threadId = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        String id = ID_PREFIX + Thread.currentThread().getId();
        if (id.equals(threadId)) {
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }
}
