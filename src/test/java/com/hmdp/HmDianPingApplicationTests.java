package com.hmdp;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SnowflakeIdWorker;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.concurrent.TimeUnit;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    RedisTemplate<String,Object> redisTemplate;
    @Resource
    ShopServiceImpl service;

    @Resource
    SnowflakeIdWorker snowflakeIdWorker;

    @Resource
    RedissonClient redissonClient;

    @Test
    public void test1() {
        Shop shop = service.getById(1);
        RedisData data = new RedisData();
        data.setData(shop);
        data.setExpireTime(LocalDateTime.now().plusSeconds(30));
        redisTemplate.opsForValue().set(RedisConstants.SHOP_KEY + shop.getId(), JSONUtil.toJsonStr(data));
    }

    @Test
    public void testSnow() throws InterruptedException {
//        // 示例LocalDateTime
//        LocalDateTime localDateTime = LocalDateTime.now();
//        System.out.println(localDateTime);
//        // 将LocalDateTime转换为ZonedDateTime（带时区）
//        ZonedDateTime zonedDateTime = localDateTime.atZone(ZoneId.systemDefault());
//
//        // 将ZonedDateTime转换为时间戳（秒）
//        long timestamp = localDateTime.toInstant(ZoneOffset.MIN).getEpochSecond();
//
//        System.out.println(zonedDateTime.toInstant());
//        System.out.println(timestamp);

        for(int i = 0; i < 100; i++) {
            Thread.sleep(100);
            System.out.println(SnowflakeIdWorker.nextId(123L));
        }
    }

    @Test
    void testSynchronized(){
//        String a = "a";
//        String b = "a";
//        if(a == b) {
//            System.out.println("true");
//        } else {
//            System.out.println(false);
//        }
        String a = new String("a");
        String b = "a";
        if(a == b.intern()) {
            System.out.println(true);
        } else {
            System.out.println(false);
        }
//        b = "a";
        if(a.intern() == b) {
            System.out.println(true);
        } else {
            System.out.println(false);
        }
    }
    @Test
    public void testRedisson() throws InterruptedException {
        RLock anylock = redissonClient.getLock("anylock");
        anylock.tryLock(1,TimeUnit.MINUTES);
    }

}
