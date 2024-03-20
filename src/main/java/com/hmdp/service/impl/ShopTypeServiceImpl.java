package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Override
    public Result queryTypes() {
        // 1、查redis
        String key = RedisConstants.SHOP_TYPE_KEY;
        String shopTypeJSON = (String) redisTemplate.opsForValue().get(key);
        if(shopTypeJSON != null) {
            List<ShopType> list = JSONUtil.toList(shopTypeJSON, ShopType.class);
            return Result.ok(list);
        }
        // 2、查数据库
        List<ShopType> list = list();
        if(list.size() == 0) {
            return Result.fail("没有查到");
        }
        // 3、写入缓存
        String shopTypeStr = JSONUtil.toJsonStr(list);
        redisTemplate.opsForValue().set(key, shopTypeStr);
        return Result.ok(list);
    }
}
