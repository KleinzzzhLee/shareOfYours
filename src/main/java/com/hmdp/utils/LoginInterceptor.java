package com.hmdp.utils;

import com.hmdp.entity.dto.UserDTO;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


/**
 * 该拦截器 拦截需要登录的请求， 如果已登陆 放行， 如果未登录 拦截
 */
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 从ThreadLocal中获取登陆用户
        UserDTO user = UserHolder.getUser();
        if(user == null) {
//            response.setStatus(401);
            return false;
        }
        return true;
    }
}
