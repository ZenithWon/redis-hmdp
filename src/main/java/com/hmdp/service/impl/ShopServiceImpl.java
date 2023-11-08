package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Autowired
    private ShopMapper shopMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);

    @Override
    public Result queryById(Long id) {
        //缓存穿透解决方案
//        return queryByIdWithPassThrough(id);

        //缓存击穿解决方案：使用互斥锁实现
//        return queryWithMutex(id);

        //缓存击穿解决方案：使用逻辑过期实现
        return queryWithLogicalExpire(id);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long shopId=shop.getId();
        if(shopId==null){
            return Result.fail("商户不存在");
        }

        shopMapper.updateById(shop);
        String shopKey=RedisConstants.CACHE_SHOP_KEY + shop.getId();
        stringRedisTemplate.delete(shopKey);

        return Result.ok();
    }

    public Result queryWithLogicalExpire(Long id){
        //NOTE:在使用逻辑过期时需要使用缓存预热，提前加载缓存

        String shopKey=RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson= stringRedisTemplate.opsForValue().get(shopKey);

        if(StrUtil.isBlank(shopJson)){
            return Result.fail("商户不存在");
        }

        RedisData redisData= JSONUtil.toBean(shopJson , RedisData.class);
        Shop shop=JSONUtil.toBean((JSONObject) redisData.getData(),Shop.class);

        if(redisData.getExpireTime().isAfter(LocalDateTime.now())){
            return Result.ok(shop);
        }

        String lock=RedisConstants.LOCK_SHOP_KEY+id;
        if(tryLock(lock)){
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try{
                    this.saveShopToRedis(id,60L);
                }catch (Exception e){
                    throw new RuntimeException(e);
                } finally {
                    unLock(lock);
                }
            });
        }

        return Result.ok(shop);

    }

    public  Result queryByIdWithPassThrough(Long id){
        String shopKey=RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson= stringRedisTemplate.opsForValue().get(shopKey);
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson , Shop.class);
            return Result.ok(shop);
        }
        if(shopJson!=null){
            return Result.fail("商户不存在");
        }

        Shop shop=shopMapper.selectById(id);
        if(shop==null){
            stringRedisTemplate.opsForValue().set(shopKey,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            return Result.fail("商户不存在");
        }

        stringRedisTemplate.opsForValue().set(shopKey,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(shop);
    }

    public Result queryWithMutex(Long id){
        String shopKey=RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson= stringRedisTemplate.opsForValue().get(shopKey);
        Shop shop =null;
        if(StrUtil.isNotBlank(shopJson)){
            shop = JSONUtil.toBean(shopJson , Shop.class);
            return Result.ok(shop);
        }
        if(shopJson!=null){
            return Result.fail("商户不存在");
        }
        String lock=RedisConstants.LOCK_SHOP_KEY+id;

        try{
            while(!tryLock(lock)){
                Thread.sleep(50);
                shopJson= stringRedisTemplate.opsForValue().get(shopKey);
                if(StrUtil.isNotBlank(shopJson)){
                    shop = JSONUtil.toBean(shopJson , Shop.class);
                    return Result.ok(shop);
                }
            }

            shopJson= stringRedisTemplate.opsForValue().get(shopKey);
            if(StrUtil.isNotBlank(shopJson)){
                shop = JSONUtil.toBean(shopJson , Shop.class);
                return Result.ok(shop);
            }

            shop=shopMapper.selectById(id);
            Thread.sleep(200);
            if(shop==null){
                stringRedisTemplate.opsForValue().set(shopKey,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
                return Result.fail("商户不存在");
            }

            stringRedisTemplate.opsForValue().set(shopKey,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        }catch (Exception e){
            throw new RuntimeException(e);
        }finally {
            unLock(lock);
        }

        return Result.ok(shop);
    }

    public void saveShopToRedis(Long id, Long expireSeconds){
        Shop shop=shopMapper.selectById(id);
        try {
            Thread.sleep(400);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        RedisData redisData=new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        redisData.setData(shop);

        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+shop.getId(), JSONUtil.toJsonStr(redisData));
    }


    private boolean tryLock(String key){
        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(key , "locked" , RedisConstants.LOCK_SHOP_TTL , TimeUnit.SECONDS);
        return BooleanUtil.isTrue(locked);
    }

    private void unLock(String key){
        stringRedisTemplate.delete(key );
    }
}
