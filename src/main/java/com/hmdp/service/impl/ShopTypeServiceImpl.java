package com.hmdp.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.sun.org.apache.bcel.internal.generic.ACONST_NULL;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    private ShopTypeMapper shopTypeMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        String shopTypeJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_TYPE_KEY);
        if(!StringUtils.isEmpty(shopTypeJson)){
            List<ShopType> typeList = JSONUtil.parseArray(shopTypeJson).toList(ShopType.class);
            return Result.ok(typeList);
        }

        List<ShopType> typeList = shopTypeMapper.selectList(
                new LambdaQueryWrapper<ShopType>().orderByAsc(ShopType::getSort)
        );

        if(CollectionUtils.isEmpty(typeList)){
            return Result.fail("商户类型不存在");
        }

        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_TYPE_KEY,JSONUtil.toJsonStr(typeList),
                RedisConstants.CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        return Result.ok(typeList);
    }
}
