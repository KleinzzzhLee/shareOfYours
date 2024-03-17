package com.hmdp.service.impl;


import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private RedisTemplate<String, String> redisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followedId, boolean isFollow) {
        // 1、获取到当前用户
        UserDTO user = UserHolder.getUser();
        String key = RedisConstants.USER_FOLLOW + user.getId().toString();
        // 2、判断isFollow，
        if(isFollow) {
            // 3、关注  新增数据
            Follow follow = new Follow();
            follow.setUserId(followedId);
            follow.setFollowUserId(user.getId());
            // 更新数据库
            if (!save(follow)) {
                log.debug("关注失败");
            } else {
                // 添加到缓存中
                redisTemplate.opsForSet().add(key, followedId.toString());
            }

        } else {
            // 4、未关注
            // 5、删除
            boolean remove = remove(new QueryWrapper<Follow>()
                    .eq("user_id", followedId)
                    .eq("follow_user_id", user.getId()));
            if(remove) {
                redisTemplate.opsForSet().remove(key, followedId.toString());
            }

        }
        return Result.ok();
    }

    @Override
    public Result isFollowed(Long followedId) {
        // 当前用户
        UserDTO user = UserHolder.getUser();
        Follow one = getOne(new QueryWrapper<Follow>().eq("user_id", followedId)
                .eq("follow_user_id", user.getId()));

        return Result.ok(one != null);
    }

    @Override
    public Result getBothFollow(Long followedId) {
        // 1、获取当前登陆的用户
        UserDTO user = UserHolder.getUser();
        // 2、获取当前用户的key 和 ta的key
        String userKey = RedisConstants.USER_FOLLOW + user.getId().toString();
        String followedKey = RedisConstants.USER_FOLLOW + followedId.toString();
        // 3、利用set命令intersect获取共同关注
        Set<String> intersect = redisTemplate.opsForSet().intersect(userKey, followedKey);
        //  4、解析这个集合
        List<Long> list = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        if(list.isEmpty()) {
            return Result.ok();
        }
        // 5、查询
        Stream<UserDTO> userDTOS = userService.listByIds(list)
                .stream()
                .map(t -> BeanUtil.copyProperties(t, UserDTO.class));

        return Result.ok(userDTOS);
    }
}
