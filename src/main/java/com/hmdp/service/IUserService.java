package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.entity.dto.LoginFormDTO;
import com.hmdp.entity.dto.Result;
import com.hmdp.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author zzzhlee
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {



    Result login(LoginFormDTO loginForm);

    Result sendCode(String account);

    Result getFeedback();


//    Result userSign();
}
