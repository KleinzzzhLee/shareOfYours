package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * keypoint 将通过redis获取锁和解锁 封装起来， 可能不同的业务都需要
 *      增加代码复用
 */
public class SimpleRedisLock implements ILock {
    RedisTemplate<String, Object> redisTemplate;
    private final String KEY_PREFIX = "lock:";
    private final String VALUE_PREFIX = UUID.randomUUID().toString(true) + "-";
    private String name;

    // 锁释放所需要的 lua脚本
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }


    public SimpleRedisLock(RedisTemplate<String, Object> redisTemplate, String name) {
        this.redisTemplate = redisTemplate;
        this.name = name;
    }

    @Override
    public boolean tryLock(long timeoutSec) {

        Boolean success = redisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name,
                VALUE_PREFIX + Thread.currentThread().getId(),
                timeoutSec,
                TimeUnit.MINUTES);
        return BooleanUtil.isTrue(success);
    }

    @Override
    public void unlock() {
        redisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                VALUE_PREFIX + Thread.currentThread().getId());
    }


//    @Override
//    public boolean unlock() {
//        String threadID = VALUE_PREFIX + Thread.currentThread().getId();
//        if(threadID.equals(redisTemplate.opsForValue().get(KEY_PREFIX + name))) {
//            return false;
//        }
//        Boolean delete = redisTemplate.delete(KEY_PREFIX + name);
//        return BooleanUtil.isTrue(delete);
//    }

}
