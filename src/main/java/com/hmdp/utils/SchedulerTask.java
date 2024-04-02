package com.hmdp.utils;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IShopService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

/**
 * kp 项目中的定时任务
 *      一、缓存商店定时更新  缓存预热
 *         1、 统计访问次数以及实时访问次数， 前者实现了热点数据的动态更新， 后者配合分布式锁防止缓存击穿问题
 *      二、点赞数量定时更新
 *         1、采用定时更新的方式，缓解了数据库的压力， 当需要更新数据量较大时，采用了分批处理避免数据库阻塞现象
 *      三、登录态的定时清除， 缓存有效性
 *          1、采用了redis中Hash存储用户登录态， 需要定期清除
 *      四、活跃用户的动态更新，同时对MQ中资源进行重新分配
 *          1、在对博客进行推送时， 通过判定采用哪种模式，活跃用户配置MQ采用推模式， 普通用户采用拉模式
 *          2、活跃用户的判定， 在用户登录时采用异步签到的策略， 判定活跃用户，
 *          3、同时对活跃用户创建相应的信息，
 */
@Slf4j
@Component
public class SchedulerTask {

    @Resource
    RedisTemplate<String, Object> redisTemplate;

    @Resource
    IShopService shopService;

    @Resource
    IBlogService blogService;

    private boolean isInit = false;


    private List<String> addShopIds = new ArrayList<>();
    private List<String> delShopIds = new ArrayList<>();

    /**
     * kp 商店缓存的定时更新
     * 数量少于原来的四分之一， 直接添加，
     * @return
     */
    private boolean addDirectly(List<String> addIds, List<String> delIds) {
        // 如果未更新， 保留原缓存信息
        if(addIds.isEmpty()) {
            return true;
        }
        // 需要添加小于原来四分之一， 直接添加
        if(addIds.size() < delIds.size()/5) {
            addShopsAtRedis(addIds, 0, addIds.size());
            return true;
        }
        return false;
    }

    private boolean addShopsAtRedis(List<String> addIds, int begin, int end) {
        List<String> ids = addIds.subList(begin, end);
        // 从数据库查出
        List<Shop> shops = shopService.listByIds(ids);
        // 添加到redis
        redisTemplate.opsForValue().multiSet(shops.stream().collect(
                Collectors.toMap(t -> RedisConstants.SHOP_KEY + t.getId().toString(), JSONUtil::toJsonStr
                )));
//        redisTemplate.opsForSet().add(RedisConstants.SHOP_CACHE_ID, ids.toArray());
        addShopIds.addAll(ids);
        return true;
    }
    private boolean deleteShopAtRedis(List<String> delIdList, int i, int delQuantityBatch) {
        int end = Math.min(i * delQuantityBatch + delQuantityBatch, delIdList.size());
        if(end == 0) {
            return false;
        }
        List<String> delIds = delIdList.subList(delQuantityBatch * i, end);
        List<String> delJSONIds = delIds.stream()
                .map(t -> RedisConstants.SHOP_KEY + t)
                .collect(Collectors.toList());
        redisTemplate.delete(delJSONIds);
//        redisTemplate.opsForSet().remove(RedisConstants.SHOP_CACHE_ID, delIds.toArray());
        delShopIds.addAll(delIds);
        return true;
    }
    private void updateRedisShopCore() {
        // 1、查询本次访问次数超过上限的id，
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(RedisConstants.SHOP_GET_TIMES);
        Map<String, Long> collect = entries.entrySet().stream().collect(Collectors.toMap(
                t -> t.getKey().toString(),
                t -> Long.valueOf(t.getValue().toString())
        ));
        Set<String> newIdsSet = collect.entrySet().stream()
                .filter(entry -> entry.getValue() > 50)
                .map(entry -> entry.getKey()).collect(Collectors.toSet());

        // 2、取出应该删除的oldIds
        Set<String> delIdsSet = redisTemplate.opsForSet().members(RedisConstants.SHOP_CACHE_ID)
                .stream()
                .filter(t -> !(newIdsSet.contains(t.toString())))
                .map(t -> t.toString())
                .collect(Collectors.toSet());
        // 将上述二者转为 List
        List<String> newIdList = new ArrayList<>(newIdsSet);
        List<String> delIdList = new ArrayList<>(delIdsSet);
        // 对新增的size进行判断，清空本次获取数量表
        if(addDirectly(newIdList, delIdList)) {
            log.debug("直接更新完成");
            return;
        }
        // 3、分批次处理
        try {
            int times = newIdList.size()/50 == 0? 1:newIdList.size()/50 + 1;
            int delQuantityBatch = delIdList.size() / times;
            for(int i = 0; i < times; i++) {
                // 取出五十个
                int end = Math.min( i * 50 + 50, newIdList.size());
                boolean isAdd = addShopsAtRedis(newIdList, 50 * i, end);
                if(isAdd) {
                    log.debug(i + "次添加成功");
                }
                // 睡眠一会
                Thread.sleep(500);

                // 删除旧的
                boolean isDelete = deleteShopAtRedis(delIdList, i, delQuantityBatch);
                if(isDelete) {
                    log.debug(i + "次删除成功");
                }
                Thread.sleep(500);
            }
        } catch (Exception e) {
            log.debug("SHOP缓存定时更新出现意外");
        }
    }
    @Scheduled(fixedDelay = 120 * 1000)
    public void updateRedisShopList() {
        log.debug("1、updateRedisShopList开始" + System.currentTimeMillis());
        updateRedisShopCore();
        // 在这个方法执行完成后， 统一删除对应id的相关信息
//        redisTemplate.delete(RedisConstants.HOT_SHOP_TIME);
//        redisTemplate.delete(RedisConstants.SHOP_GET_TIMES);
        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public <K, V> Object execute(RedisOperations<K, V> operations) throws DataAccessException {
                if(!CollectionUtils.isEmpty(addShopIds)) {
                    redisTemplate.opsForSet().add(RedisConstants.SHOP_CACHE_ID, addShopIds.toArray());
                }
                if(!CollectionUtils.isEmpty(delShopIds)) {
                    redisTemplate.opsForSet().remove(RedisConstants.SHOP_CACHE_ID, delShopIds.toArray());
                }
                redisTemplate.delete(RedisConstants.HOT_SHOP_TIME);
                redisTemplate.delete(RedisConstants.SHOP_GET_TIMES);
                redisTemplate.delete(RedisConstants.SHOP_SUDDEN_TIMES);
                return null;
            }
        });
        // 修改列表
        addShopIds.clear();
        delShopIds.clear();
        log.debug("1、updateRedisShopList结束" + System.currentTimeMillis());
    }

    /**
     * kp 用户点赞的定时任务
     */
    private void init() {
        // 1、查出所有的博客
        List<Blog> list = blogService.list();
        // 2、向缓存中存入三张表的信息
        list.forEach((blog)->{
            redisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                public <K, V> Object execute(RedisOperations<K, V> operations) throws DataAccessException {
                    redisTemplate.opsForHash().put(RedisConstants.BLOG_LIKE_ISUPDATE, blog.getId().toString(), String.valueOf(0));
                    redisTemplate.opsForHash().put(RedisConstants.BLOG_LIKE_TOTAL, blog.getId().toString(), blog.getLiked().toString());
                    return null;
                }
            });
            });
    }
    private List<Long> getNeedUpdateLikeBlog() {
        Map<Object, Object> updateMap = redisTemplate.opsForHash().entries(RedisConstants.BLOG_LIKE_ISUPDATE);
        List<Long> updateBolgList = new ArrayList<>();
//        log.debug("进入blog的异步1");
        updateMap.forEach((key, value) -> {

            if ( !String.valueOf(value).equals("0")) {
                updateBolgList.add(Long.valueOf(String.valueOf(key)));
            }
        });
        return updateBolgList;
    }
    private String updateBlogLikesInRedis(List<Long> updateBolgList, int i) {
        String blogId = updateBolgList.get(i).toString();
        List<Object> res = redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public <K, V> Object execute(RedisOperations<K, V> operations) throws DataAccessException {
                redisTemplate.opsForHash().get(RedisConstants.BLOG_LIKE_TOTAL, blogId);
                redisTemplate.opsForHash().put(RedisConstants.BLOG_LIKE_ISUPDATE, blogId, String.valueOf(0));
                return null;
            }
        });
        return (String) res.get(0);
    }
    @Scheduled(fixedDelay = 60 * 1000)
    public void initAndUpdateBlogLikes() {
        log.debug("2、initAndUpdateBlogLikes开始执行" + System.currentTimeMillis());
        if(!isInit) {
            init();
            isInit = true;
        }
        try {
            // 1、从redis当中获取哪个博客点赞需要更新
            List<Long> updateBolgList = getNeedUpdateLikeBlog();
//            log.debug("进入blog的异步2");
            int total = updateBolgList.size();
            boolean isSleep = false;
            if (total > 1000) {
                isSleep = true;
            }
//            log.debug("开始blog的异步循环");
            // 2、在BLOG_LIKE_TOTAL 和 对应的 BLOG_LIKE_CACHE 查询，
            for (int i = 0; i < total; i++) {
                if (isSleep && i % 1000 == 0) {
                    Thread.sleep(3000);
                }
                // 3、修改三个表的信息
                String count = updateBlogLikesInRedis(updateBolgList, i);
                UpdateWrapper<Blog> blogUpdateWrapper = new UpdateWrapper<>();
                blogUpdateWrapper.setSql("liked =  " + count).eq("id", updateBolgList.get(i));
                boolean update = blogService.update(blogUpdateWrapper);
                if(!update) {
                    throw new Exception();
                }
            }
            Thread.sleep(1000 * 20);
        }catch(Exception e){
            log.debug("出现了BUG");
        }

        log.debug("2、initAndUpdateBlogLikes结束执行" + System.currentTimeMillis());
    }



    /**
     * kp 缓存登录态的定时清除
     *  为避免一次清除过多，采用分批次
     */
    private int i = 0;
    @Scheduled(fixedDelay = 10 * 60 * 1000)
    public void clearLoginStatus() {
        log.debug("3、clearLoginStatus开始执行" + System.currentTimeMillis());
        Map<Integer, List<String>> expireToken = new HashMap<>();
        expireToken = getExpireLoginStatus();
        Map<Integer, List<String>> delKeys = new HashMap<>();
        expireToken.forEach((key, value) -> {
            List<String> list = new ArrayList<>();
            value.forEach(t -> {
                list.add(t + ".timestamp");
                list.add(t + ".id");
                list.add(t + ".nickName");
                list.add(t + ".icon");
            });
            delKeys.put(key, list);
        });
        // 进行删除
        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public <K, V> Object execute(RedisOperations<K, V> operations) throws DataAccessException {
                delKeys.forEach((key, value) -> {
                    redisTemplate.opsForHash().delete(RedisConstants.LOGIN_USER_KEY + key, value.toArray());
                });
                return null;
            }
        });
        log.debug("3、clearLoginStatus结束执行" + System.currentTimeMillis());
    }

    private Map<Integer, List<String>> getExpireLoginStatus() {
        HashOperations<String, Object, Object> hashOps = redisTemplate.opsForHash();
        Map<String, Map<Object,Object>> entries = new HashMap<>();
//        Map<Object, Object> entries = new HashMap<>();
        // 将所有的hash取出，分表记录， i为hash表的后缀
        for(int i = 0; i  < 500;i++) {
            entries.put(String.valueOf(i),redisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY + i));
        }
//        i = i % 500;
        // kp 在取出的所有键值中， 只保留时间戳的键值对
        Map<String, Map<String, String>> collect = entries.entrySet().stream()
                .filter(e -> e.getValue().size() > 0)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().entrySet()
                                .stream()
                                .filter(t -> t.getKey().toString().contains(".timestamp"))
                                .collect(Collectors.toMap(
                                        t -> t.getKey().toString().split("\\.")[0],
                                        t -> t.getValue().toString()
                                ))
                ));
        Map<Integer, List<String>> expireToken = new HashMap<>();
        // kp 筛选出过期的时间戳
        long now = System.currentTimeMillis() / 1000;
        collect.forEach((key, value) -> {
            List<String> list = new ArrayList<>();
            value.forEach((k, v) -> {
                if(now - Long.parseLong(v) > 10 * 60) {
                    list.add(k);
                }
            });
            expireToken.put(Integer.parseInt(key), list);
        });

        return expireToken;
    }


    @Resource
    private RabbitAdmin rabbitAdmin;

    @Resource
    private RabbitTemplate rabbitTemplate;

    /**
     * kp 定时对活跃用户进行更新
     */
    @Scheduled(fixedDelay = 7 * 24 * 60 * 60 * 1000)
    public void updateActiveUser() {
        // 1、获取本次签到次数表
        Map<Long, Integer> signTimesNow = redisTemplate.opsForHash().entries(RedisConstants.USER_SING_TIMES_NOW)
                .entrySet().stream()
                .filter(t -> Integer.parseInt(t.getValue().toString()) > 3)
                .collect(Collectors.toMap(
                        t -> Long.valueOf(t.getKey().toString()),
                        t -> Integer.valueOf(t.getValue().toString())
                ));
        // 2、获取上次签到表
        Map<Long, Integer> signTimesLast = redisTemplate.opsForHash().entries(RedisConstants.USER_SIGN_TIMES_LAST)
                .entrySet().stream()
                .collect(Collectors.toMap(
                        t -> Long.valueOf(t.getKey().toString()),
                        t -> Integer.valueOf(t.getValue().toString())
                ));
        // 采用set 的  deleteIds = signTimeLast - signTimeLast交signTimeNow
        Set<Long> signNew = signTimesNow.keySet();
        Set<Long> signLast = signTimesLast.keySet();
        // 交
        Set<Long> intersect = new HashSet<>(signLast);
        intersect.retainAll(signNew);
        // 差
        Set<Long> difference = new HashSet<>(signLast);
        difference.removeAll(intersect);
        // kp difference 存放的是应该删除的所有用户的id
        List<Object> objects = redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public <K, V> Object execute(RedisOperations<K, V> operations) throws DataAccessException {
                difference.forEach(key -> {
                    redisTemplate.opsForSet().members(RedisConstants.USER_FOLLOW + key);
                });
                return null;
            }
        });
        List<Set<String>> collect = objects.stream()
                .map(t -> (Set<String>) t)
                .collect(Collectors.toList());
        Long[] deleteIds = difference.toArray(new Long[0]);
        Map<String, List<String>> delete = new HashMap<>();
        for(int i = 0; i < deleteIds.length; i++) {
            List<String> l = new ArrayList<>(collect.get(i));
            String key = deleteIds[i].toString();

            delete.put(key, l);
        }
        deleteAtRabbitMQ(delete);
        Map<Long, Integer> allSign = redisTemplate.opsForHash().entries(RedisConstants.USER_SING_TIMES_NOW)
                .entrySet().stream()
                .collect(Collectors.toMap(
                        t -> Long.valueOf(t.getKey().toString()),
                        t -> Integer.valueOf(t.getValue().toString())
                ));
        List<String> deleteSigns = allSign.keySet().stream().map(Object::toString).collect(Collectors.toList());

        // 更新redis的信息， 将 上一周期的删除，
        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public <K, V> Object execute(RedisOperations<K, V> operations) throws DataAccessException {
                // 更新 旧的签到次数表
                redisTemplate.rename(RedisConstants.USER_SING_TIMES_NOW, RedisConstants.USER_SIGN_TIMES_LAST);
                // 删除 活跃用户表
                redisTemplate.delete(RedisConstants.USER_ACTIVE_LIST);
                    List<String> signBitmap = deleteSigns.stream().map(t -> RedisConstants.USER_SIGN_PREFIX + t).collect(Collectors.toList());
                redisTemplate.delete(signBitmap);
                return null;
            }
        });

    }

    public void deleteAtRabbitMQ(Map<String, List<String>> follow) {

        follow.forEach((key, list) -> {
            // 1、删除 用户的汇总队列
            rabbitAdmin.deleteQueue(RabbitConstants.CONCENTRATED_QUEUE + key, true, false);
            // 2、删除 用户的接受队列
            list.forEach(id -> {
                rabbitAdmin.deleteQueue(key + RabbitConstants.ACCEPT_QUEUE + id, true, true);
            });
        });


    }
}
