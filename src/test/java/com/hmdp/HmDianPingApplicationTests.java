package com.hmdp;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.entity.dto.UserDTO;
import com.hmdp.entity.vo.MailVO;
import com.hmdp.service.impl.MailServiceImpl;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RabbitConstants;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SnowflakeIdWorker;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import javax.annotation.Resource;
import java.time.*;
import java.util.concurrent.TimeUnit;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private MailServiceImpl mailService;

    @Resource
    RedisTemplate<String,Object> redisTemplate;
    @Resource
    ShopServiceImpl service;

    @Resource
    SnowflakeIdWorker snowflakeIdWorker;

    @Resource
    RedissonClient redissonClient;

    @Test
    public void testMailService() {
        MailVO mail = new MailVO();
        mail.setTo("1924774423@qq.com");
        mail.setCode("123456");
        mailService.senMail(mail);
    }

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

    @Test
    public void testCache() {
        UserDTO userDTO = new UserDTO();
        userDTO.setId(1000L);
        userDTO.setIcon("");
        userDTO.setNickName("1000Lasdfzxcv");
        for(int i = 0; i < 100; i++){
            String name = RandomUtil.randomString(6);
            String name2 = RandomUtil.randomString(6);
            String name3 = RandomUtil.randomString(6);

//            redisTemplate.opsForValue().set("test:login:expire:" + name , JSONUtil.toJsonStr(userDTO));
//            redisTemplate.opsForValue().set("test:login:expire:" + name3 , JSONUtil.toJsonStr(userDTO));
//            redisTemplate.opsForValue().set("test:login:expire:" +  name2 ,JSONUtil.toJsonStr(userDTO));
            redisTemplate.opsForHash().put("testHash4", name + ".nickName","1234asdfzxc");
            redisTemplate.opsForHash().put("testHash4", name + ".icon", "");
            redisTemplate.opsForHash().put("testHash4", name + ".id", "1111");
            redisTemplate.opsForHash().put("testHash4", name + ".timestamp", String.valueOf(System.currentTimeMillis()/1000));
            redisTemplate.opsForHash().put("testHash5", name + ".nickName","1234asdfzxc");
            redisTemplate.opsForHash().put("testHash5", name + ".icon", "");
            redisTemplate.opsForHash().put("testHash5", name + ".id", "1111");
            redisTemplate.opsForHash().put("testHash5", name + ".timestamp", String.valueOf(System.currentTimeMillis()/1000));
            redisTemplate.opsForHash().put("testHash6", name + ".nickName","1234asdfzxc");
            redisTemplate.opsForHash().put("testHash6", name + ".icon", "");
            redisTemplate.opsForHash().put("testHash6", name + ".id", "1111");
            redisTemplate.opsForHash().put("testHash6", name + ".timestamp", String.valueOf(System.currentTimeMillis()/1000));
//            redisTemplate.opsForHash().put("test", name, String.valueOf(System.currentTimeMillis()/1000));
        }


    }

    @Resource
    RabbitAdmin rabbitAdmin;
    @Resource
    RabbitTemplate rabbitTemplate;
    @Test
    public  void testQueueDurable() {
//        rabbitAdmin.declareQueue(new Queue("test", true));
//        rabbitAdmin.declareQueue(new Queue("test2", true, true, false));
        Message receive = rabbitTemplate.receive("1010." + RabbitConstants.ACCEPT_QUEUE + "2");
        byte[] body = receive.getBody();
        String temp = new String(body).replaceAll("\\\\", "");
        String jsonStr = temp.substring(1, temp.length() - 1);
        Blog blog = JSONUtil.toBean(jsonStr, Blog.class);
        System.out.println(blog);
    }

    @Test
    public void testJSONUtil() {
        String str = "{\"images\":\"/imgs/blogs/8/10/b0bd3a18-69d8-42e7-9092-d3fa652431c4.jpg\",\"title\":\"tt\",\"userId\":1,\"content\":\"sadfasdfsafasdfsdfasdfasdfsf\",\"id\":59,\"shopId\":1,\"timestamp\":1711873374345}";
        System.out.println(str);
        Blog blog = JSONUtil.toBean(str, Blog.class);
        System.out.println(blog);
    }

}
