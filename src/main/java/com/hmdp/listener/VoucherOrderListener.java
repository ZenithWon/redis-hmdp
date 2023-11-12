package com.hmdp.listener;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


//spring初始化后会开启该线程处理订单
@Slf4j
@Component
public class VoucherOrderListener implements Runnable{
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private VoucherOrderListener selfProxy;
    @Autowired
    private IVoucherOrderService voucherOrderService;

    private static final String ORDER_QUEUE_NAME="stream.orders";

    private static final ExecutorService SEC_KILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SEC_KILL_ORDER_EXECUTOR.submit(selfProxy);
    }


    @Override
    public void run() {
        while (true){
            try{
                List<MapRecord<String, Object, Object>> orderList = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1" , "c1") ,
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)) ,
                        StreamOffset.create(ORDER_QUEUE_NAME , ReadOffset.lastConsumed())
                );
                if (orderList==null||orderList.isEmpty()){
                    continue;
                }

                MapRecord<String, Object, Object> entries = orderList.get(0);
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(entries.getValue() , new VoucherOrder() , true);

                if(!voucherOrderService.createOrder(voucherOrder)){
                    throw new RuntimeException("创建订单失败");
                }

                stringRedisTemplate.opsForStream().acknowledge(ORDER_QUEUE_NAME,"g1",entries.getId());
                log.debug("处理订单 {} 成功",voucherOrder.getId());
            }catch (Exception e){
                log.error("处理订单异常",e);
                handlePendingList();
            }

        }
    }

    private void handlePendingList() {
        while(true){
            try{
                List<MapRecord<String, Object, Object>> orderList = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1" , "c1") ,
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(ORDER_QUEUE_NAME , ReadOffset.from("0"))
                );
                if (orderList==null||orderList.isEmpty()){
                    break;
                }

                MapRecord<String, Object, Object> entries = orderList.get(0);
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(entries.getValue() , new VoucherOrder() , true);

                if(!voucherOrderService.createOrder(voucherOrder)){
                    throw new RuntimeException("创建订单失败");
                }

                stringRedisTemplate.opsForStream().acknowledge(ORDER_QUEUE_NAME,"g1",entries.getId());
            }catch (Exception e){
                log.error("pendingList异常",e);
            }
        }
    }
}
