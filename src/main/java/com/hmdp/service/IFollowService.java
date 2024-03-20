package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.entity.dto.Result;
import com.hmdp.entity.Follow;

public interface IFollowService extends IService<Follow> {

    Result follow(Long followedId, boolean isFollow);

    Result isFollowed(Long followedId);

    Result getBothFollow(Long followedId);
}
