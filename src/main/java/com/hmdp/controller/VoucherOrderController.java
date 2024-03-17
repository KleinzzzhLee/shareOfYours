package com.hmdp.controller;


import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.entity.Voucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.impl.SeckillVoucherServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author zzzhlee
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    RedisTemplate<String, Object> redisTemplate;
    @Resource
    IVoucherOrderService voucherOrderService;
    @PostMapping("/seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
//        String authorization = request.getHeader("authorization");
//        if(StrUtil.isBlank(authorization)) {
//            return Result.fail("请登录");
//        }
//        String userJSON  = (String) redisTemplate.opsForValue().get(RedisConstants.LOGIN_USER_KEY + authorization);
//        User user = JSONUtil.toBean(userJSON, User.class);
//        return voucherOrderService.purchaseSecKillVoucher(user.getId(),voucherId);
        return voucherOrderService.purchaseSecKillVoucher(voucherId);
    }
}
