package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

/**
 * 该拦截器 拦截所有请求，
 *   不管是否登录， 均放行
 *
 *   这样做的目的： 使得登录后的一切操作都会对token进行刷新
 */
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private RedisTemplate<String, Object> redisTemplate;

    public RefreshTokenInterceptor(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1、获取到请求头的token信息
        String token = RedisConstants.LOGIN_USER_KEY +  request.getHeader(RedisConstants.LOGIN_AUTHORIZATION);
        // 2.1如果信息不存在， 直接放行
        if(StrUtil.isEmpty(token)){
            return true;
        }
        // 2.2如果信息存在，
        String userJSON = (String) redisTemplate.opsForValue().get(token);
        if(userJSON == null) {
            return true;
        }
        UserDTO userDTO = JSONUtil.toBean(userJSON, UserDTO.class);
        // 3、判断用户是否存在
        // 3.1如果不存在， 直接执行

        // 3.2存在， 刷新token的有效期
        redisTemplate.expire(token, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 4.将token存入到ThreadLocal中，
        UserHolder.saveUser(userDTO);
        return true;
//        // 1.获取请求头中的token
//        String token = request.getHeader("authorization");
//        if (StrUtil.isBlank(token)) {
//            return true;
//        }
//        // 2.基于TOKEN获取redis中的用户
//        String key  = LOGIN_USER_KEY + token;
//        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
//        // 3.判断用户是否存在
//        if (userMap.isEmpty()) {
//            return true;
//        }
//        // 5.将查询到的hash数据转为UserDTO
//        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
//        // 6.存在，保存用户信息到 ThreadLocal
//        UserHolder.saveUser(userDTO);
//        // 7.刷新token有效期
//        stringRedisTemplate.expire(key, LOGIN_USER_TTL, TimeUnit.MINUTES);
//        // 8.放行
//        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户
        UserHolder.removeUser();
    }
}
