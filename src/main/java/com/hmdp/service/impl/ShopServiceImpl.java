package com.hmdp.service.impl;

import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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

    @Override
    public Result queryById(Long id) {
        String shopKey=RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson= stringRedisTemplate.opsForValue().get(shopKey);
        if(!StringUtils.isEmpty(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson , Shop.class);
            return Result.ok(shop);
        }

        Shop shop=shopMapper.selectById(id);
        if(shop==null){
            return Result.fail("商户不存在");
        }

        stringRedisTemplate.opsForValue().set(shopKey,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(shop);
    }
}
