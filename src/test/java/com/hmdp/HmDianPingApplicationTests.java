package com.hmdp;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    RedisTemplate<String,Object> redisTemplate;
    @Resource
    ShopServiceImpl service;

    @Test
    public void test1() {
        Shop shop = service.getById(1);
        RedisData data = new RedisData();
        data.setData(shop);
        data.setExpireTime(LocalDateTime.now().plusSeconds(30));
        redisTemplate.opsForValue().set(RedisConstants.SHOP_KEY + shop.getId(), JSONUtil.toJsonStr(data));
    }

}
