package com.hmdp.service.impl;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

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

    @Override
    public Result seckillVoucher(Long voucherId) {
        SeckillVoucher seckillVoucher = seckillVoucherMapper.selectOne(
                new LambdaQueryWrapper<SeckillVoucher>().eq(SeckillVoucher::getVoucherId , voucherId)
        );

        if(seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀活动尚未开始");
        }
        if(seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀活动已经结束");
        }

        Integer stock = seckillVoucher.getStock();
        if(stock<1){
            return Result.fail("库存不足");
        }

        Long userId=UserHolder.getUser().getId();

        SimpleRedisLock simpleRedisLock = new SimpleRedisLock(stringRedisTemplate , "order:" + userId);
        boolean lock = simpleRedisLock.tryLock(2);
        if(!lock){
            return Result.fail("购买失败请重试");
        }

        try{
            return selfProxy.createVoucherOrder(userId,voucherId);
        } finally {
            simpleRedisLock.unLock();
        }
    }

    @Transactional
    public Result createVoucherOrder(Long userId,Long voucherId){
        Integer count = voucherOrderMapper.selectCount(
                new LambdaQueryWrapper<VoucherOrder>().eq(VoucherOrder::getUserId , userId).eq(VoucherOrder::getVoucherId , voucherId)
        );
        if(count>0){
            return Result.fail("您已抢购过该订单");
        }

        int res = seckillVoucherMapper.updateStockWithLock(voucherId);
        if(res<=0){
            return Result.fail("抢购失败");
        }

        VoucherOrder voucherOrder=new VoucherOrder();
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setId(redisIdWorker.netId(RedisConstants.ORDER_KEY));

        voucherOrderMapper.insert(voucherOrder);

        return Result.ok(voucherOrder.getId());
    }
}
