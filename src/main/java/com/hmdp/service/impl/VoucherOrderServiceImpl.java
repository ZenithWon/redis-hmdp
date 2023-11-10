package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Autowired
    private SeckillVoucherMapper seckillVoucherMapper;
    @Autowired
    private VoucherMapper voucherMapper;
    @Autowired
    private VoucherOrderMapper voucherOrderMapper;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private IVoucherOrderService selfProxy;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedissonClient redissonClient;

    @Override
    public Result seckillVoucher(Long voucherId) {
        SeckillVoucher seckillVoucher = seckillVoucherMapper.selectOne(
                new LambdaQueryWrapper<SeckillVoucher>().eq(SeckillVoucher::getVoucherId , voucherId)
        );

        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀活动尚未开始");
        }
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀活动已经结束");
        }

        Integer stock = seckillVoucher.getStock();
        if (stock < 1) {
            return Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();
        RLock lock = redissonClient.getLock("local:order:" + userId);
        try {
            boolean isLock = lock.tryLock(1 , 20 , TimeUnit.SECONDS);
            if (!isLock) {
                return Result.fail("购买失败请重试");
            }
            return selfProxy.createVoucherOrder(userId , voucherId);
        } catch (InterruptedException e) {
            return Result.fail("购买失败请重试");
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public Result createVoucherOrder(Long userId , Long voucherId) {
        Integer count = voucherOrderMapper.selectCount(
                new LambdaQueryWrapper<VoucherOrder>().eq(VoucherOrder::getUserId , userId).eq(VoucherOrder::getVoucherId , voucherId)
        );
        if (count > 0) {
            return Result.fail("您已抢购过该订单");
        }

        int res = seckillVoucherMapper.updateStockWithLock(voucherId);
        if (res <= 0) {
            return Result.fail("抢购失败");
        }

        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setId(redisIdWorker.netId(RedisConstants.ORDER_KEY));

        voucherOrderMapper.insert(voucherOrder);

        return Result.ok(voucherOrder.getId());
    }

    //使用异步实现方案优化秒杀
    @Override
    public Result seckillOptimize(Long voucherId) {
        String stockStr = stringRedisTemplate.opsForValue().get(RedisConstants.SECKILL_STOCK_KEY + voucherId);
        if(StrUtil.isBlank(stockStr)){
            return Result.fail("该优惠券不存在");
        }

        Integer stock=Integer.valueOf(stockStr);
        if(stock<=0){
            return Result.fail("优惠券已经被抢光了");
        }
        //todo:库存减1，生成订单加入集合并返回订单信息
        return Result.ok("Completed");
    }
}
