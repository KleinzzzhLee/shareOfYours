package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.SnowflakeIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;
    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     *
     */
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    /**
     *   keypoint 抢购秒杀券
     *              在单体架构下 基于锁的实现
     *     todo 解决超卖问题 ， 利用乐观锁 ： 在更改时 进行检测， 看看和之前查的是否一致
     *          实现一人最多买一张， 利用悲观锁 ： 在购买前 去表中 判断是否购买过。
     *                              注意：1、spring中事物的特性 是基于动态代理实现的，
     *                                   2、锁的释放和事物的提交 二者的顺序问题
     *                                   3、悲观锁synchronized()的实现
     * @return
     */
    public Result purchaseSecKillVoucher(Long voucherId) {

//        keypoint 单体模式 解决方案
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
//        // keypoint 为保证一人一单， 注意点：  1、悲观锁往往锁的是对象  2、应先释放锁，再提交事物
//        // 获取登录用户的id
//        /**
//         * keypoint 字符串的intern方法，
//         *  intern是从字符串常量池中搜索是否存在该字符串， 如果存在。直接取出； 不存在，在字符串常量池中添加在返回
//         */
//        Long userId = UserHolder.getUser().getId();
//        synchronized (userId.toString().intern()) {
//            // 获得当前类的代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.purchaseSecKillVoucherOfOneOly(seckillVoucher);
//        }
        // keypoint spring框架下的事物注解@Trasanction 是利用的AOP和动态代理机制 当在一个方法中调用事物时， 会失效
        // 以下为解决措施
        IVoucherOrderService proxyClass = (IVoucherOrderService) AopContext.currentProxy();
        return proxyClass.purchaseSecKillVoucherByRedis(voucherId);
    }
    /**
     * 利用事物机制
     * @param seckillVoucher 秒杀券
     * @return
     */
    @Transactional
    public  Result purchaseSecKillVoucherByLock(SeckillVoucher seckillVoucher) {
        // 验证数据库中不存在该用户的券
        QueryWrapper<VoucherOrder> orderQueryWrapper = new QueryWrapper<>();
        orderQueryWrapper.eq("user_id", UserHolder.getUser().getId());
        VoucherOrder voucherOrder = voucherOrderService.getOne(orderQueryWrapper);
        if(voucherOrder != null) {
            return Result.fail("只许购买一张");
        }
        VoucherOrder order = new VoucherOrder();
        // keypoint 利用乐观锁， 在修改之前再次检查剩余是否足够
        // 如果足够才能购买， 解决了超卖的问题
        boolean update = seckillVoucherService.update()
                .gt("stock", 0)
                .setSql("stock = stock - 1")
                .eq("voucher_id", seckillVoucher.getVoucherId())
                .update();
        if (!update) {
            return Result.fail("库存不足");
        }
        // 4、keypoint 获取到唯一自增的主键，雪花算法
        Long id = SnowflakeIdWorker.nextId(1234L);
        // 5、向数据库中增加订单信息
        order.setId(id);
        order.setUserId(UserHolder.getUser().getId());
        order.setVoucherId(seckillVoucher.getVoucherId());
        boolean save = voucherOrderService.save(order);
        if (!save) {
            return Result.fail("抢购失败");
        }

        return Result.ok(order.getId());
    }

    /**
     * keypoint 利用缓存解决 集群模式下锁失效的问题
     * @param voucherId
     * @return
     */
    @Transactional
    public Result purchaseSecKillVoucherByRedis(Long voucherId) {
        // 1、查询秒杀券是否存在
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if(voucher == null ) {
            return Result.fail("秒杀券不存在");
        }
        // 1.2 查询当前时间是否合适
        if(voucher.getBeginTime().isAfter(LocalDateTime.now()) || voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("未在时间范围");
        }
        // 2、查询秒杀券的数量是否足够
        if(voucher.getStock() < 1) {
            return Result.fail("很遗憾，您未抢到");
        }
        // 3、查询个人是否买过， 查询本人身份是否存在
        UserDTO user = UserHolder.getUser();

        QueryWrapper<VoucherOrder> voucherOrderQueryWrapper = new QueryWrapper<>();
        voucherOrderQueryWrapper.eq("user_id", user.getId()).eq("voucher_id", voucherId);
        if(voucherOrderService.getOne(voucherOrderQueryWrapper) != null) {
            return Result.fail("限购一张");
        }
        Long threadID = Thread.currentThread().getId();
        // 4、keypoint 向redis中存入标识信息 设置过期时间,
        // todo  封装它， 因为， 在每次获取锁时，也可能存在别的
        // todo 这里要使用final关键字， 即使中间出现意外， 也不会导致再次获取无法成功
        SimpleRedisLock srl = new SimpleRedisLock(redisTemplate, RedisConstants.CACHE_VOUCHER_USER_KEY);
        VoucherOrder voucherOrder = new VoucherOrder();
        try {
//        String key = RedisConstants.CACHE_VOUCHER_USER_KEY + user.getId();
//        Boolean addFlag = redisTemplate.opsForValue().setIfAbsent(key, UUID.randomUUID() + "-" + threadID);
//        Boolean expireFlag = redisTemplate.expire(key, RedisConstants.CACHE_VOUCHER_USER_TTL, TimeUnit.MINUTES);
            if (!srl.tryLock(2)) {
                return Result.fail("设置缓存失败");
            }
            // 5、对秒杀券进行修改
            boolean update = seckillVoucherService.update()
                    .eq("voucher_id", voucher.getVoucherId())
                    .gt("stock", 0)
                    .setSql("stock = stock - 1")
                    .update();
            if (!update) {
                return Result.fail("操作数据库异常");
            }
            // 6、对订单表修改
            voucherOrder.setId(SnowflakeIdWorker.nextId(123L));
            voucherOrder.setUserId(user.getId());
            voucherOrder.setVoucherId(voucherId);
            if (!voucherOrderService.save(voucherOrder)) {
                return Result.fail("插入订单失败");
            }
        } finally {
            // 8、删除缓存
//        Boolean delete = redisTemplate.delete(RedisConstants.CACHE_VOUCHER_USER_KEY + user.getId());
            if(!srl.unlock()) {
                return Result.fail("删除缓存标志异常");
            }
        }

        // 7、返回结果
        return Result.ok(voucherOrder.getId());
    }
}
