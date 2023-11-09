package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);

    public void set(String key, Object value, Long time, TimeUnit unit){
        //带有过期时间的set
        String jsonStr= JSONUtil.toJsonStr(value);
        stringRedisTemplate.opsForValue().set(key,jsonStr,time,unit);
    }

    public void logicalSet(String key, Object value, Long time, TimeUnit unit){
        //带有逻辑过期时间的set
        RedisData redisData=new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        String jsonStr=JSONUtil.toJsonStr(redisData);
        stringRedisTemplate.opsForValue().set(key,jsonStr);
    }

    public <R,ID> R getWithPassThrough(String keyPrefix, ID id ,Class<R> type, Function<ID,R> dbCallBack,
                                       Long time, TimeUnit unit){
        //缓存穿透解决方案，设置空值的取
        String key=keyPrefix+id.toString();
        String json=stringRedisTemplate.opsForValue().get(key);

        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json,type);
        }

        if (json != null) {
            return null;
        }

        R r=dbCallBack.apply(id);

        if(r==null){
            this.set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        this.set(key,r,time,unit);
        return r;
    }

    public <R,ID> R getWithLogicalExpire(String keyPrefix, String lockPrefix,ID id ,Class<R> type, Function<ID,R> dbCallBack,
                                      Long time, TimeUnit unit){
        //缓存击穿解决方案1：开启独立线程构造缓存
        String key=keyPrefix+id.toString();
        String json=stringRedisTemplate.opsForValue().get(key);

        if(StrUtil.isBlank(json)){
            return null;
        }
        RedisData redisData=JSONUtil.toBean(json,RedisData.class);
        R r=JSONUtil.toBean((JSONObject) redisData.getData(),type);

        if(redisData.getExpireTime().isAfter(LocalDateTime.now())){
            return r;
        }

        String lock=lockPrefix+id;
        if(tryLock(lock)){
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    R dbR=dbCallBack.apply(id);
                    this.logicalSet(key,dbR,time,unit);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    unLock(lock);
                }
            });
        }
        return r;
    }

    public <R,ID> R getWithMutex(String keyPrefix, String lockPrefix,ID id ,Class<R> type, Function<ID,R> dbCallBack,
                                         Long time, TimeUnit unit){
        //缓存击穿解决方案2：使用互斥锁的循环等待
        String key=RedisConstants.CACHE_SHOP_KEY + id;
        String json= stringRedisTemplate.opsForValue().get(key);
        R r =null;
        if(StrUtil.isNotBlank(json)){
            r = JSONUtil.toBean(json , type);
            return r;
        }
        if(json!=null){
            return null;
        }
        String lock=RedisConstants.LOCK_SHOP_KEY+id;

        try{
            while(!tryLock(lock)){
                Thread.sleep(50);
                json= stringRedisTemplate.opsForValue().get(key);
                if(StrUtil.isNotBlank(json)){
                    r = JSONUtil.toBean(json , type);
                    return r;
                }
            }

            json= stringRedisTemplate.opsForValue().get(key);
            if(StrUtil.isNotBlank(json)){
                r = JSONUtil.toBean(json , type);
                return r;
            }

            r=dbCallBack.apply(id);
            Thread.sleep(200);
            if(r==null){
                this.set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }

            this.set(key,r,time,unit);
        }catch (Exception e){
            throw new RuntimeException(e);
        }finally {
            unLock(lock);
        }

        return r;
    }

    private boolean tryLock(String key){
        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(key , "locked" , RedisConstants.LOCK_SHOP_TTL , TimeUnit.SECONDS);
        return BooleanUtil.isTrue(locked);
    }

    private void unLock(String key){
        stringRedisTemplate.delete(key );
    }

}
