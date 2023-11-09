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
import com.hmdp.utils.CacheClient;
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
    @Autowired
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //这里可以使用多种缓存方案
//        Shop shop = cacheClient.getWithPassThrough(RedisConstants.CACHE_SHOP_KEY , id , Shop.class , shopMapper::selectById ,
//                RedisConstants.CACHE_SHOP_TTL , TimeUnit.MINUTES);
        //NOTE:还可以使用getWithLogicalExpire
//        Shop shop=cacheClient.getWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY, RedisConstants.LOCK_SHOP_KEY, id , Shop.class , shopMapper::selectById ,
//                RedisConstants.CACHE_SHOP_TTL , TimeUnit.MINUTES);

        Shop shop=cacheClient.getWithMutex(RedisConstants.CACHE_SHOP_KEY, RedisConstants.LOCK_SHOP_KEY, id , Shop.class , shopMapper::selectById ,
                RedisConstants.CACHE_SHOP_TTL , TimeUnit.MINUTES);
        if(shop!=null){
            return Result.ok(shop);
        }else{
            return Result.fail("商户不存在");
        }

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



    public void saveShopToRedis(Long id, Long expireSeconds){
        //测试用的工具方法
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

}
