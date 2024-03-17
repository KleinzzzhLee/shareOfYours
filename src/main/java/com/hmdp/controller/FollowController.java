package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author zzzhlee
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    IFollowService followService;

    @PutMapping("/{followedId}/{isFollow}")
    public Result follow(@PathVariable("followedId") Long followedId, @PathVariable("isFollow") boolean isFollow) {
        return followService.follow(followedId, isFollow);
    }

    @GetMapping("/or/not/{followedId}")
    public Result isFollowed(@PathVariable("followedId") Long followedId) {
        return followService.isFollowed(followedId);
    }

    @GetMapping("/common/{followedId}")
    public Result bothFollow(@PathVariable("followedId") Long followedId) {
        return followService.getBothFollow(followedId);
    }

}
