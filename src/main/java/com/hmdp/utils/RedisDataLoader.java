package com.hmdp.utils;

import cn.hutool.core.collection.ListUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * keypoint 这个类是为了防止redis当中购买秒杀券的信息在redis当中突然消息
 * 所以在项目启动后，
 */
@Component
public class RedisDataLoader implements ApplicationRunner {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

//    @Resource
//    private

    private static DefaultRedisScript<Long> CREATE_CONSUMER = new DefaultRedisScript<>();
    static {
        CREATE_CONSUMER.setLocation(new ClassPathResource("luas/createConsumer.lua"));
        CREATE_CONSUMER.setResultType(Long.class);
    }
    @Override
    public void run(ApplicationArguments args) throws Exception {
        // 1、从数据库的表中获取到 秒杀券信息 和  购买order信息
        QueryWrapper<SeckillVoucher> seckillVoucherQueryWrapper = new QueryWrapper<>();
        seckillVoucherQueryWrapper.ge("stock", 0);
        // 1.1 秒杀券信息
        List<SeckillVoucher> seckillVoucherList = seckillVoucherService.list(seckillVoucherQueryWrapper);
        // 1.2 收集为Map key：id  value：stock
        Map<Long, Integer> seckillVoucherMap = seckillVoucherList.stream().collect(
                Collectors.toMap(SeckillVoucher::getVoucherId,
                        SeckillVoucher::getStock,
                        (exist, replace) -> exist)
        );

        // 1.3 order信息
        List<VoucherOrder> orderList = voucherOrderService.list();
        // 1.4 收集为Map key：id  value：userId
        Map<Long, List<VoucherOrder>> orderMap = orderList.stream()
                .filter(order -> seckillVoucherMap.containsKey(order.getVoucherId()))
                .collect(
                        Collectors.groupingBy(VoucherOrder::getVoucherId));

        // 2、 将上述二者存入到redis中
        // 2.1 异步任务， 将秒杀券的数量存入redis hash形式
        Thread voucherThread = new Thread(new Runnable() {
            @Override
            public void run() {
                seckillVoucherMap.forEach((id, stock) -> {
                    redisTemplate.opsForHash().put(RedisConstants.SECKILLVOUCHER_STOCK_HASH,
                            id.toString(), stock.toString());
                });
            }
        });
        // 2.2 异步任务： 将购买者的信息存入redis  形式 list  key为voucherId
        Thread orderThread = new Thread(new Runnable() {
            @Override
            public void run() {
                orderMap.forEach((voucherId, list) ->{
                    list.forEach((voucherOrder) -> {
                        redisTemplate.opsForSet().add(
                                RedisConstants.SECKILLVOUCHER_ORDER_LIST + voucherId.toString(),
                                voucherOrder.getUserId().toString());
                    });
                });
            }
        });

        // 3、 开启线程
        voucherThread.start();
        orderThread.start();
    }
}
