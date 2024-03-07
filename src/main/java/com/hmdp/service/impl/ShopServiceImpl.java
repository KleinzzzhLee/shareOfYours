package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.CacheClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author zzzhlee
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;
    @Resource
    private CacheClient redisClient;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     *  现在是利用了缓存空对象来解决 缓存穿透的问题，
         *  缓存穿透： 查询的数据在缓存和数据库中都不存在的情况下， 在缓存层和持久层都不
         *  会命中，请求都压到持久层，就是数据库中，造成了数据库的崩溃
     *
     * @param id
     * @return
     */
    @Override
    public Result queryShop(Long id) {
        Shop shop = redisClient.getOneByMutex(id, RedisConstants.SHOP_KEY + id,
                RedisConstants.LOCK_SHOP_KEY + id, Shop.class,
                RedisConstants.SHOP_TTL, TimeUnit.MINUTES, RedisConstants.NULL_SHOP_TTL, TimeUnit.MINUTES,
                this::getById);
        if(shop == null) {
            return Result.fail("查询失败");
        }
        return Result.ok(shop);
    }

//    /**
//     * 这个仅仅实现了 todo 缓存穿透
//     * @param id
//     * @return
//     */
//    public Result queryShopByPassThrough(Long id) {
//        // 利用redis， 将菜品信息缓存到redis， 每次查询先从redis当中查询
//        // todo 如何利用布隆过滤器解决缓存穿透的问题？？？？
//        // 1、先从redis当中查询
//        String shopJSON = (String) redisTemplate.opsForValue().get(RedisConstants.SHOP_KEY + id);
//        // isNotBlank 只有在字符串存在值的时候返回true
//        if(StrUtil.isNotBlank(shopJSON)) {
//            Shop shop = JSONUtil.toBean(shopJSON, Shop.class);
//            return Result.ok(shop);
//        }
//        // shopJSON为null 表示 数据库中不存在该项数据
//        if(shopJSON != null ) {
//            return Result.fail("商品信息不存在");
//        }
//        // 1.2 查询到， 直接从redis中将数据取出， 封装到shop对象中 直接返回
//
//        // 2、 对数据库查询
//        Shop shopById = getById(id);
//        if(shopById == null) {
//            // 2.2 未查询到 将该id存入， value设置为null
////            redisTemplate.opsForValue().set(RedisConstants.SHOP_KEY + id, );
//            // 为解决缓存穿透的问题， 将不存在的id记录在redis中
//            redisTemplate.opsForValue().set(RedisConstants.SHOP_KEY + id, "", RedisConstants.NULL_SHOP_TTL, TimeUnit.MINUTES);
//            return Result.fail("该商店不存在");
//        }
//        // 2.3 查询到， 对商店进行缓存，存入到redis当中
//        redisTemplate.opsForValue().set(RedisConstants.SHOP_KEY + id, JSONUtil.toJsonStr(shopById), RedisConstants.SHOP_TTL, TimeUnit.MINUTES);
//        return Result.ok(shopById);
//
//    }
//
//    /**
//     * 采用互斥锁的方式解决缓存穿透问题
//     *      高频缓存丢失， 大致大量的请问访问数据库
//     *      todo 实现了  1、利用空值防止缓存穿透
//     *                  2、利用互斥锁防止缓存击穿
//     */
//    private Shop queryWithMutex(Long id) {
//        // todo 在缓存中查询数据， 存在直接返回 不存在对数据库进行查询（采用互斥锁的方式）
//        String shopJSON;
//        boolean lock = false;
//        Shop shop;
//        try {
//            // 1、对缓存进行查询
//            while (!lock) {
//                shopJSON = (String) redisTemplate.opsForValue().get(RedisConstants.SHOP_KEY + id);
//                if(StrUtil.isNotBlank(shopJSON)){
//                    return JSONUtil.toBean(shopJSON, Shop.class);
//                }
//                if(shopJSON != null) {
//                    redisTemplate.opsForValue().set(RedisConstants.SHOP_KEY + id, "", RedisConstants.NULL_SHOP_TTL);
//                    return null;
//                }
//                // 2、未查询到， 上锁 todo 通过向redis存放数据进行上锁
//                // 2.2 失败， 一段时间后再次对缓存进行查询
//                lock = lock(id);
//                Thread.sleep(100);
//            }
//            // 2.1 获得成功 进行数据库的查询
//            // 3、查询数据库，
//            shop = getById(id);
//            Thread.sleep(1000);
//            // 4、未查询到， todo 增加空值缓存 防止缓存穿透
//            if (shop == null) {
//                redisTemplate.opsForValue().set(RedisConstants.SHOP_KEY + id, "", RedisConstants.NULL_SHOP_TTL, TimeUnit.MINUTES);
//                return null;
//            }
//            // 5、查询到，向缓存中放入
//            redisTemplate.opsForValue().set(RedisConstants.SHOP_KEY + id, JSONUtil.toJsonStr(shop), RedisConstants.SHOP_TTL, TimeUnit.MINUTES);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        } finally {
//            // 6、释放锁
//            unlock(id);
//        }
//        return shop;
//    }
//
//    /**
//     *  防止 缓存击穿
//     *  todo 实现思路：
//         * 1、从缓存中取出热点数据
//         * 2、判断该热点数据是否为空值 ： 防止缓存穿透
//         * 2.1、为空： 直接返回
//         * 3、将数据反序列化，
//         * 4、判断逻辑过期时间
//         * 5. 已过期：判断互斥锁是否开启， 未过期直接返回信息
//         * 	1、开启互斥锁
//         * 		需要doubleCheck 因为这时候获取到锁 也有一种可能是其他线程做完了重建释放了的锁
//         * 	2、开启新线程， 再次从数据库中取出数据，更新缓存信息
//         * 	3、关闭互斥锁
//         * 6、将原数据直接返回
//     * @return
//     */
//    private Shop queryWithLogicalExpire(Long id) {
//        // 1
//        String dataJSON = (String) redisTemplate.opsForValue().get(RedisConstants.SHOP_KEY + id);
//        // 2
//        if(StrUtil.isBlank(dataJSON)) {
//            return null;
//        }
//        // 3
//        RedisData data = JSONUtil.toBean(dataJSON, RedisData.class);
//
//        Shop shop = JSONUtil.toBean((JSONObject) data.getData(), Shop.class);
//        LocalDateTime expireTime = data.getExpireTime();
//        // 4
//        if(expireTime.isAfter(LocalDateTime.now())) {
//            return shop;
//        }
//        // 5.1
//        boolean isLock = lock(shop.getId());
//        if(isLock) {
//            // 5.2
//            if(expireTime.isBefore(LocalDateTime.now())) {
//                // 开启一个新的线程， 从数据库中查询数据， 放入缓存中
//                CACHE_REBUILD_EXECUTOR.submit(() -> {
//                    try {
//                        this.saveShopToRedis(id, 20L);
//                    } catch (Exception e) {
//                        throw new RuntimeException(e);
//                    } finally {
//                        unlock(id);
//                    }
//                });
//            }
//        }
//
//        // 6
//        return shop;
//    }
//
//    /**
//     * 利用redis实现互斥锁
//     * @param id
//     * @return
//     */
//    private boolean lock(Long id) {
//        Boolean rs = redisTemplate.opsForValue().setIfAbsent(RedisConstants.LOCK_SHOP_KEY + id, RandomUtil.randomString(3));
//        return BooleanUtil.isTrue(rs);
//    }
//    private boolean unlock(Long id) {
//        Boolean rs = redisTemplate.delete(RedisConstants.LOCK_SHOP_KEY + id);
//        return BooleanUtil.isTrue(rs);
//    }
//
//    private void saveShopToRedis(Long id, Long expireTime) throws InterruptedException {
//        Shop shop = getById(id);
//        Thread.sleep(20);
//        RedisData data = new RedisData();
//        data.setData(shop);
//        data.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));
//
//        redisTemplate.opsForValue().set(RedisConstants.SHOP_KEY + id, JSONUtil.toJsonStr(data));
//
//    }


    /**
     * 更新商店信息，采用先更新数据库，再删除缓存中的信息
     *  注意， 此处存在事物的控制， 确保数据的一致性
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Result updateShopById(Shop shop) {
        // 更新商店
        boolean update = updateById(shop);
//        int a = 1/ 0;
        // 删除缓存
        Boolean delete = redisTemplate.delete(RedisConstants.SHOP_KEY + shop.getId());
        if(update && delete) {
            log.debug("更新成功");
        }
        return Result.ok();
    }
}
