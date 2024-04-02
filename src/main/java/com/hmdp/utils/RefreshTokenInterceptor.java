package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import com.hmdp.entity.dto.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.List;

/**
 * 该拦截器 拦截所有请求，
 *   不管是否登录， 均放行
 *
 *   这样做的目的： 使得登录后的一切操作都会对token进行刷新
 */
@Slf4j
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private RedisTemplate<String, Object> redisTemplate;

    public RefreshTokenInterceptor(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1、获取到请求头的token信息
        String token = request.getHeader(RedisConstants.LOGIN_AUTHORIZATION);
       // String token = RedisConstants.LOGIN_USER_KEY  +  ;
        // 2.1如果信息不存在， 直接放行
        if(StrUtil.isEmpty(token)){
            return true;
        }
        // 2.2如果信息存在，
        //  kp hash
        // String userJSON = (String) redisTemplate.opsForValue().get(token);
        String[] userKeys = {token + ".nickName", token + ".icon", token + ".id", token + ".timestamp"};
        int i = Math.abs(token.hashCode() % 500);
        List<Object> objects = redisTemplate.opsForHash().multiGet(RedisConstants.LOGIN_USER_KEY + i, Arrays.asList(userKeys));

        if(objects.get(0) == null) {
            return true;
        }
        // 判断登录是否过期
        long lastTime = Long.parseLong((String) objects.get(3));
        long now = System.currentTimeMillis() / 1000;
        if(now - lastTime > 10 * 60) {
            log.debug("登录时间过期");
            return true;
        }

        String nickName = (String) objects.get(0);
        String icon = (String) objects.get(1);
        String id = (String) objects.get(2);
        UserDTO userDTO = new UserDTO();
        userDTO.setIcon(icon);
        userDTO.setNickName(nickName);
        userDTO.setId(Long.valueOf(id));
        // 3、判断用户是否存在
        // 3.1如果不存在， 直接执行

        // 3.2存在， 刷新token的有效期
//        redisTemplate.expire(token, 300, TimeUnit.MINUTES);
        redisTemplate.opsForHash().put(RedisConstants.LOGIN_USER_KEY + i, token + ".timestamp", String.valueOf(now));
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
