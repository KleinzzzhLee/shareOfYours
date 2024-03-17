package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.lang.intern.InternUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.SnowflakeIdWorker;
import com.hmdp.utils.UserHolder;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;
    @Resource
    private IVoucherOrderService voucherOrderService;

    // keypoint 利用redisson 解除在redis上的原子性问题
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    private static final DefaultRedisScript<Long> REDIS_SCRIPT = new DefaultRedisScript<>();

    static {
        REDIS_SCRIPT.setLocation(new ClassPathResource("luas/seckill.lua"));
        REDIS_SCRIPT.setResultType(Long.class);
    }

    // 线程池
    private static final ExecutorService threadPool = Executors.newSingleThreadExecutor();


    @PostConstruct
    private void init() {
        threadPool.submit(new syncHandleSeckillVoucherOrder());
    }

    private class syncHandleSeckillVoucherOrder implements Runnable {

        @Override
        public void run() {
            while(true) {
                try {
                    // 1、获取消息队列中的订单信息
                    List<MapRecord<String, Object, Object>> list = redisTemplate.opsForStream().read(
                            Consumer.from("group", "consumer"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed()));
                    // 1。2 进行判断
                    if(list == null || list.size() == 0) {
                        Thread.sleep(2);
                        continue;
                    }
                    // 2、将订单信息拆箱
                    MapRecord<String, Object, Object> entries = list.get(0);
                    Map<Object, Object> values = entries.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 4、 完成下单
                    handleVoucherOrder(voucherOrder);
                    // 5、Ack确认
                    redisTemplate.opsForStream().acknowledge("stream.orders","group", entries.getId());

                } catch (InterruptedException e) {
                    // keypoint 如果消息未被确认， 就会进入padding-list  应再去padding-list判断
                    handlePaddingList();
                }
            }
        }


        /**
         *  从stream的padding-list中获取 未确认的订单
         */
        private void handlePaddingList() {
            while(true) {
                try {
                    // 1、获取padding-list中的未确认的订单信息
                    List<MapRecord<String, Object, Object>> list = redisTemplate.opsForStream().read(
                            Consumer.from("group", "consumer"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create("stream.orders", ReadOffset.from("0")));
                    // 1。2 进行判断
                    if(list == null || list.size() == 0) {
                        // 未读到 说明padding-list中不存在消息
                        log.debug("未读取到信息");
                        break;
                    }
                    log.debug("读取到信息");
                    // 2、将订单信息拆箱
                    MapRecord<String, Object, Object> entries = list.get(0);
                    Map<Object, Object> values = entries.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 4、 完成下单
                    handleVoucherOrder(voucherOrder);
                    // 5、Ack确认
                    redisTemplate.opsForStream().acknowledge("stream.orders",
                            "group",
                            entries.getId());
                } catch (InterruptedException e) {
                    // keypoint 如果padding-list中的消息再次未被确认， 继续下一次循环， 依然可以读到
                    log.debug("处理padding-list出现异常");
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }

        }
    }


    /**
     *  完成下单
     * @param voucherOrder
     * @throws InterruptedException
     */
    public void handleVoucherOrder(VoucherOrder voucherOrder) throws InterruptedException {
        // 1、获取用户
        Long userId = voucherOrder.getUserId();
        // 2、创建锁
        // keypoint 通过redisson方式获取锁
        //      1、可重试锁： 源码内部 采用了重试机制， 如果获取一次不成功，等待一段时间继续获取，直到时间耗尽
        //      2、可重入： 通过在缓存内存入hash结构， 以当前线程id为key value为重试次数，
        RLock lock = redissonClient.getLock(RedisConstants.CACHE_VOUCHER_USER_KEY);
        // 3、获取锁
        if (!lock.tryLock(RedisConstants.CACHE_VOUCHER_USER_TTL, TimeUnit.MINUTES)) {
            log.debug("获取锁失败");
            return;
        }
        try {
            // 5、从数据库中获取订单信息
            int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
            if(count > 0) {
                // 用户已经购买过了
                log.error("不允许重复下单！");
                return;
            }

            // 修改库存
            boolean success = seckillVoucherService.update()
                    .eq("voucher_id", voucherOrder.getVoucherId())
                    .gt("stock", 0)
                    .setSql("stock = stock - 1")
                    .update();
            if(!success) {
                log.debug("库存不足");
                return;
            }
            // 6、下单
            save(voucherOrder);
        } finally {
            lock.unlock();
        }
    }


    /**
     *  todo 开启秒杀异步的方式： 提高秒杀效率
     */
    @Override
    public Result purchaseSecKillVoucher(Long voucherId) {
        // 0、将要购买的消息放入到缓存
        VoucherOrder order = new VoucherOrder();
        // 0.1 用户的id  userId
        UserDTO user = UserHolder.getUser();
        order.setUserId(user.getId());
        // 0.2 券的id   voucherId
        order.setVoucherId(voucherId);
        // 0.3 雪花算法生成的订单id
        Long id = SnowflakeIdWorker.nextId(SnowflakeIdWorker.ORDER_PREFIX);
        order.setId(id);

        String orderJSON = JSONUtil.toJsonStr(order);
        // 2、调用lua脚本 在脚本中判断购买资格
        Long res = redisTemplate.execute(REDIS_SCRIPT,
                ListUtil.empty(),
                id.toString(), user.getId().toString(), voucherId.toString());
        int r = res.intValue();
        if( r != 0) {
            log.debug("无购买资格");
            return Result.fail("无购买资格");
        }
        log.debug("已成功购买");
        return Result.ok();
    }




//    /**
//     *   keypoint 抢购秒杀券
//     *              在单体架构下 基于锁的实现
//     *     todo 解决超卖问题 ， 利用乐观锁 ： 在更改时 进行检测， 看看和之前查的是否一致
//     *          实现一人最多买一张， 利用悲观锁 ： 在购买前 去表中 判断是否购买过。
//     *                              注意：1、spring中事物的特性 是基于动态代理实现的，
//     *                                   2、锁的释放和事物的提交 二者的顺序问题
//     *                                   3、悲观锁synchronized()的实现
//     * @return
//     */
//    public Result purchaseSecKillVoucher(Long voucherId) {
//        // keypoint spring框架下的事物注解@Trasanction 是利用的AOP和动态代理机制 当在一个方法中调用事物时， 会失效
//        // 以下为解决措施
//        return purchaseSecKillVoucherByRedis(voucherId);
//    }

//    /**   keypoint FIRST
//     * 利用事物机制
//     * @param seckillVoucher 秒杀券
//     * @return
//     */
//    @Transactional
//    public  Result purchaseSecKillVoucherByLock(SeckillVoucher seckillVoucher) {
//        // 验证数据库中不存在该用户的券
//        QueryWrapper<VoucherOrder> orderQueryWrapper = new QueryWrapper<>();
//        orderQueryWrapper.eq("user_id", UserHolder.getUser().getId());
//        VoucherOrder voucherOrder = voucherOrderService.getOne(orderQueryWrapper);
//        if(voucherOrder != null) {
//            return Result.fail("只许购买一张");
//        }
//        VoucherOrder order = new VoucherOrder();
//        // keypoint 利用乐观锁， 在修改之前再次检查剩余是否足够
//        // 如果足够才能购买， 解决了超卖的问题
//        boolean update = seckillVoucherService.update()
//                .gt("stock", 0)
//                .setSql("stock = stock - 1")
//                .eq("voucher_id", seckillVoucher.getVoucherId())
//                .update();
//        if (!update) {
//            return Result.fail("库存不足");
//        }
//        // 4、keypoint 获取到唯一自增的主键，雪花算法
//        Long id = SnowflakeIdWorker.nextId(1234L);
//        // 5、向数据库中增加订单信息
//        order.setId(id);
//        order.setUserId(UserHolder.getUser().getId());
//        order.setVoucherId(seckillVoucher.getVoucherId());
//        boolean save = voucherOrderService.save(order);
//        if (!save) {
//            return Result.fail("抢购失败");
//        }
//
//        return Result.ok(order.getId());
//    }

/*
    //        keypoint 单体模式 解决方案 SECOND : 解决了一人一单的问题
//        1、查询秒杀券是否存在
//        SeckillVoucher seckillVoucher =  seckillVoucherService.getById(voucherId);
//        if(seckillVoucher == null ) {
//            return Result.fail("秒杀券已过期，请刷新网页");
//        }
//
//        // 2、 查看秒杀是否开启
//        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now()) || seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("不在秒杀时间内");
//        }
//        // 2、判断秒杀券数量是否足够
//        if(seckillVoucher.getStock() < 1) {
//            return Result.fail("库存不足");
//        }
//        // 3、对秒杀券数量修改
////        seckillVoucher.setStock(seckillVoucher.getStock() - 1);
////        if(!seckillVoucherService.updateById(seckillVoucher)) {
////            return Result.fail("抢购失败");
////        }
//        // 6、返回结果 订单id
//        /**
//         * keypoint 字符串的intern方法，
//         *  intern是从字符串常量池中搜索是否存在该字符串， 如果存在。直接取出； 不存在，在字符串常量池中添加在返回
//         */
//        Long userId = UserHolder.getUser().getId();
//        keypoint 为保证一人一单， 注意点：
//          1、悲观锁往往锁的是对象
//          2、应先提交事物，再释放锁
//          3、在方法内部调用@Trasactional标注的方法， 不能使事物生效，
//                    原因： 在spring中，该注解的实现是通过代理机制实现的， 必须获取到该代理对象 ， 通过代理对象调用才可
//        synchronized (userId.toString().intern()) {
//            // 获得当前类的代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.purchaseSecKillVoucherOfOneOly(seckillVoucher);
//        }

    /**
     *  采用异步的方法
     * @param voucherId
     * @return
     */


//    /**
//     * keypoint 利用缓存解决 集群模式下锁失效的问题
//     *      失效原因： 通过悲观锁保证只有一个线程执行该程序， 仅在单体模式下生效，如果是集群。 则无法解决
//     * keypoint：
//     *      version 1： 通过redis记录当前进行的事物， 在使用当前UUID标识线程， 做到 一人一单  使用UUID是因为线程的id
//     * @param voucherId
//     * @return
//     */
//    public Result purchaseSecKillVoucherByRedis(Long voucherId) {
//        // 1、查询秒杀券是否存在
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        if (voucher == null) {
//            return Result.fail("秒杀券不存在");
//        }
//        // 1.2 查询当前时间是否合适
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now()) || voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("未在时间范围");
//        }
//        // 2、查询秒杀券的数量是否足够
//        if (voucher.getStock() < 1) {
//            return Result.fail("很遗憾，您未抢到");
//        }
//        UserDTO user = UserHolder.getUser();
//
//
//
//        // 4、keypoint 向redis中存入标识信息 设置过期时间,
//        // todo  封装它， 因为， 在每次获取锁时，也可能存在别的
//        // todo 这里要使用final关键字， 即使中间出现意外， 也不会导致再次获取无法成功
//
////        SimpleRedisLock srl = new SimpleRedisLock(redisTemplate, RedisConstants.CACHE_VOUCHER_USER_KEY);
//        // keypoint 利用redisson实现
//        RLock rLock = redissonClient.getLock(RedisConstants.CACHE_VOUCHER_USER_KEY + user.getId());
////        rLock.tryLock(60, TimeUnit.SECONDS);
//        try {
//            if (!rLock.tryLock(60, TimeUnit.SECONDS)) {
//                return Result.fail("设置缓存失败");
//            }
//            VoucherOrderServiceImpl proxy = (VoucherOrderServiceImpl) AopContext.currentProxy();
//            // 7、返回结果
//            return proxy.test(voucher, voucherId);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        } finally {
//            rLock.unlock();
//        }
//    }
//
//
//    @Transactional
//    public Result test(SeckillVoucher voucher, Long voucherId) {
//        VoucherOrder voucherOrder = new VoucherOrder();
//        UserDTO user = UserHolder.getUser();
//
//    //        String key = RedisConstants.CACHE_VOUCHER_USER_KEY + user.getId();
//    //        Boolean addFlag = redisTemplate.opsForValue().setIfAbsent(key, UUID.randomUUID() + "-" + threadID);
//    //        Boolean expireFlag = redisTemplate.expire(key, RedisConstants.CACHE_VOUCHER_USER_TTL, TimeUnit.MINUTES);
//        // 3、查询个人是否买过， 查询本人身份是否存在
//        if (query().eq("user_id", user.getId()).eq("voucher_id", voucherId).count() > 0) {
//            return Result.fail("限购一张");
//        }
//
//        // 5、对秒杀券进行修改
//        boolean update = seckillVoucherService.update()
//                .eq("voucher_id", voucher.getVoucherId())
//                .gt("stock", 0)
//                .setSql("stock = stock - 1")
//                .update();
//        if (!update) {
//            return Result.fail("操作数据库异常");
//        }
//        // 6、对订单表修改
//        voucherOrder.setId(SnowflakeIdWorker.nextId(123L));
//        voucherOrder.setUserId(user.getId());
//        voucherOrder.setVoucherId(voucherId);
//        if (!save(voucherOrder)) {
//            return Result.fail("插入订单失败");
//        }
//
//        return Result.ok(voucherOrder.getId());
//    }
}
