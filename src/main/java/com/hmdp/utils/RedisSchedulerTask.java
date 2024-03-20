package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import jodd.util.CollectionUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
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
 * 该类是为了开启redis中的定时任务
 */
@Slf4j
@Component
public class RedisSchedulerTask {

    @Resource
    RedisTemplate<String, Object> redisTemplate;

    @Resource
    IShopService shopService;


    private List<String> addShopIds = new ArrayList<>();
    private List<String> delShopIds = new ArrayList<>();

    /**
     * 数量少于原来的四分之一， 直接添加，
     * @return
     */
    private boolean addDirectly(List<String> addIds, List<String> delIds) {
        // 如果未更新， 保留原缓存信息
        if(addIds.isEmpty()) {
            return true;
        }
        // 需要添加小于原来四分之一， 直接添加
        if(addIds.size() < delIds.size()/4) {
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
    @Scheduled(fixedDelay = 5*60 * 1000)
    public void updateRedisShopList() {
        log.debug("updateRedisShopList开始");
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
                return null;
            }
        });
        // 修改列表
        addShopIds.clear();
        delShopIds.clear();
        log.debug("updateRedisShopList结束");
    }

}
