package com.hmdp.listener;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.entity.VoucherOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

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

    private static final String ORDER_QUEUE_NAME="stream.orders";

    private static final ExecutorService SEC_KILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SEC_KILL_ORDER_EXECUTOR.submit(selfProxy);
    }


    @Override
    public void run() {
        while (true){
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
            //todo:需要更新数据库：减库存，创建订单
            log.debug("处理一个订单=>{}",voucherOrder.toString());
            stringRedisTemplate.opsForStream().acknowledge(ORDER_QUEUE_NAME,"g1",entries.getId());
        }
    }
}
