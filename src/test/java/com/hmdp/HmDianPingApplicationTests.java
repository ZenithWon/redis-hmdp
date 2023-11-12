package com.hmdp;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class HmDianPingApplicationTests {
    @Autowired
    private IShopService shopService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisIdWorker redisIdWorker;

    private ExecutorService executorService= Executors.newFixedThreadPool(500);

    @Test
    public void test1(){
        shopService.saveShopToRedis(2L,60L);
    }

    @Test
    public void testIdGenerator(){
        int count=30;
        CountDownLatch latch=new CountDownLatch(count);
        Runnable task=()->{
            for(int i=0;i<10;i++){
                long id=redisIdWorker.nextId("order");
                System.out.println("Generator Id => "+id);
            }
            latch.countDown();
        };

        long begin=System.currentTimeMillis();

        for(int i=0;i<count;i++){
            executorService.submit(task);
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        long end=System.currentTimeMillis();
        System.out.println("Runtime:"+(end-begin)+"ms");
    }



}
