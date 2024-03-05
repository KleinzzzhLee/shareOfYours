package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.security.Key;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class TestClient {
    @Resource
    RedisTemplate<String, Object> redisTemplate;

    // 创建一个线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    /**
     * 向缓存中存入数据
     * @param key 键
     * @param value JSON形式的value
     * @param time 存储时间
     * @param unit 时间单位
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     *  从缓存中获取数据
     * @param key 键
     * @param clazz value的Class对象
     * @return
     * @param <T> 键值对象
     */
    private <T> T get(String key, Class<T> clazz) {
        String strJSON = (String) redisTemplate.opsForValue().get(key);
        return JSONUtil.toBean(strJSON, clazz);
    }

    /**
     *          todo 缓存穿透 : 改进： 使用布隆过滤方式
     *   这是利用了 空值的办法解决缓存穿透的问题
     * @param id 数据库中的主键
     * @param key 键
     * @param clazz 键值类型
     * @param valueTime 有效值的存放时长
     * @param valueUnit 有效值的时间单位
     * @param nullTime 空值的存放时长
     * @param nullUnit 空值的时间单位
     * @param function 方法获取到
     * @return 对象
     * @param <ID> 主键类型
     * @param <R> 返回类型
     */
    public <ID,R> R getOneByPassThrough(
            ID id, String key, Class<R> clazz,
            Long valueTime, TimeUnit valueUnit, Long nullTime, TimeUnit nullUnit,
            Function<ID, R> function){
        // 1、从redis中读出
        String strJSON = (String) redisTemplate.opsForValue().get(key);
        // 2、判断是否为空
        if (StrUtil.isNotBlank(strJSON)) {
            // 不为空， 更新存在时间，并返回
            R obj = JSONUtil.toBean(strJSON, clazz);
            // 更新存在时间
            set(key, obj, valueTime, valueUnit);
            return obj;
        }
        // 3、判断是否为""，
        if(strJSON != null) {
            return null;
        }

        // 4、对数据库进行查询
        R res = function.apply(id);
        // 5、如果为null 在缓存中赋空值
        if(res == null) {
            set(key, "", nullTime, nullUnit);
            return null;
        }
        // 6、向缓存中封装数据，存在时间
        set(key, JSONUtil.toJsonStr(res), valueTime, valueUnit);
        // 7、返回
        return res;
    }

    // 缓存击穿： 采用互斥锁的方式
    public <ID,R> R getOneByMutex(
            ID id, String key, String lockKey, Class<R> clazz,
            Long valueTime, TimeUnit valueUnit, Long nullTime, TimeUnit nullUnit,
            Function<ID, R> function) {
        // 1、从缓存中获取数据
        String json;
        R res;
        boolean lock = false;
        try {
            // 1、对缓存进行查询
            while (!lock) {
                json = (String) redisTemplate.opsForValue().get(key);
                if(StrUtil.isNotBlank(json)){
                    set(key, json, valueTime, valueUnit);
                    return JSONUtil.toBean(json, clazz);
                }
                if(json != null) {
                    set(key + id, "", nullTime, nullUnit);
                    return null;
                }
                // 2、未查询到， 上锁 todo 通过向redis存放数据进行上锁
                // 2.2 失败， 一段时间后再次对缓存进行查询
                lock = tryLock(lockKey);
                Thread.sleep(100);
            }
            // 2.1 获得成功 进行数据库的查询
            // 3、查询数据库，
            res = function.apply(id);
            Thread.sleep(1000);
            // 4、未查询到， todo 增加空值缓存 防止缓存穿透
            if (res == null) {
                set(key, "", nullTime, nullUnit);
                return null;
            }
            // 5、查询到，向缓存中放入
            set(key, JSONUtil.toJsonStr(res), valueTime, valueUnit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 6、释放锁
            unLock(lockKey);
        }
        return res;
    }

    // 缓存击穿： 使用逻辑过期的方式判断
    public <ID,R> R getOneByLogicalExpire(
            ID id, String key, String lockKey, Class<R> clazz,
            Long valueTime, TimeUnit valueUnit,
            Function<ID, R> function) {
        // 1、从缓存中获取
        String json = (String) redisTemplate.opsForValue().get(key);
        // 2、判断数据是否为空值 防止缓存穿透
        if(StrUtil.isBlank(json)) {
            // 3.1、为空，直接返回null，
            return  null;
        }
        // 3.2、不为空， 判断逻辑过期时间是否超出
        RedisData data = JSONUtil.toBean(json, RedisData.class);
        R res = JSONUtil.toBean((JSONObject)data.getData(), clazz);
        LocalDateTime expireTime = data.getExpireTime();
        // 4.1、不超出， 直接返回
        if(expireTime.isAfter(LocalDateTime.now())) {
            return res;
        }
        // 4.2、超出， 另起一个线程，从数据库中查询， 并更新缓存
        if(tryLock(lockKey)) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                RedisData temp = new RedisData();
                temp.setData(function.apply(id));
                temp.setExpireTime(LocalDateTime.now().plusSeconds(5L * 60));

                set(key, JSONUtil.toJsonStr(temp), valueTime, valueUnit);
            });
        }
        // 5、当前线程直接返回原值
        return res;
    }

    public boolean tryLock(String key) {
        Boolean lock = redisTemplate.opsForValue().setIfAbsent(key, "lock");
        return BooleanUtil.isTrue(lock);
    }
    public boolean unLock(String key) {
        Boolean lock = redisTemplate.delete(key);
        return BooleanUtil.isTrue(lock);
    }
}
