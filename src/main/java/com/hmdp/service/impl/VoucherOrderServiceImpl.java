package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.listener.VoucherOrderListener;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.*;

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
    @Autowired
    private VoucherOrderListener voucherOrderListener;

    //加载Lua脚本
    private static final DefaultRedisScript<Long> SEC_KILL_SCRIPT;
    static{
        SEC_KILL_SCRIPT=new DefaultRedisScript<>();
        SEC_KILL_SCRIPT.setLocation(new ClassPathResource("/luaScript/seckill.lua"));
        SEC_KILL_SCRIPT.setResultType(Long.class);
    }


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
        voucherOrder.setId(redisIdWorker.nextId(RedisConstants.ORDER_KEY));

        voucherOrderMapper.insert(voucherOrder);

        return Result.ok(voucherOrder.getId());
    }

    //使用异步实现方案优化秒杀
    @Override
    public Result seckillOptimize(Long voucherId) {
        //执行Lua脚本，检查是否可以下单，可以将库存减1
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId(RedisConstants.ORDER_KEY);
        Long killRes = stringRedisTemplate.execute(
                SEC_KILL_SCRIPT , Collections.emptyList() , Long.toString(voucherId) ,
                Long.toString(userId),String.valueOf(orderId)
        );

        if(killRes==null){
            throw new RuntimeException("抢购失败");
        }

        if(killRes.equals(1L)){
            return Result.fail("优惠券已经被抢光");
        }

        if(killRes.equals(2L)){
            return Result.fail("您已经购买过该优惠券");
        }

        if(killRes.equals(3L)){
            return Result.fail("优惠券不存在");
        }
        //NOTE：Lua脚本已经添加至消息队列，这里只需要判断是否下单成功然后返回结果
        return Result.ok(orderId);
    }

    @Override
    @Transactional
    public boolean createOrder(VoucherOrder voucherOrder){
        Long voucherId = voucherOrder.getVoucherId();
        Long userId = voucherOrder.getUserId();

        Integer count = voucherOrderMapper.selectCount(
                new LambdaQueryWrapper<VoucherOrder>().eq(VoucherOrder::getVoucherId , voucherId).eq(VoucherOrder::getUserId , userId)
        );
        if(count>0){
            return false;
        }

        if (seckillVoucherMapper.updateStockWithLock(voucherId) <= 0) {
            return false;
        }

        if(voucherOrderMapper.insert(voucherOrder)<=0){
            return false;
        }

        return true;
    }
}
