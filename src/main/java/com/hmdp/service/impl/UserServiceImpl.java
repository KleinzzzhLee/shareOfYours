package com.hmdp.service.impl;

import cn.hutool.Hutool;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result sedCode(String phone, HttpSession session) {
        // todo 前端的工作 1、检验手机号的格式
        if(RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        // 2、生成验证码
        String code = RandomUtil.randomNumbers(6);
        // todo 利用邮箱生成验证码
        // 3、将验证码存入到session中， todo 是不是利用 手机号存储更好？
        session.setAttribute("code", code);

        // 4、发送验证码
        log.debug("发送的验证码为=" + code);
        // 5、展示结果
        return Result.ok();
//        //1. 校验手机号
//        if (RegexUtils.isPhoneInvalid(phone)) {
//            //2.如果不符合，返回错误信息
//            return Result.fail("手机号格式错误");
//        }
//
//        //3. 符合，生成验证码
//        String code = RandomUtil.randomNumbers(6);
//        //4. 保存验证码到session
//        session.setAttribute("code",code);
//        //5. 发送验证码
//        log.debug("发送短信验证码成功，验证码:{}",code);
//        //返回ok
//        return Result.ok();
    }

    /**
     *  用户登录
     * @param loginForm 登录信息
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1、检验手机号
        // 2、检验验证码 或者 检验密码 todo 密码在数据库中存储要采用加密形式
        // todo 检验是否注册
        // 3、不正确， 返回错误提示信息
        // 4、 正确， 保存信息到 session中
        //
        //1. 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        //2. 校验验证码
        Object cacheCode = session.getAttribute("code");
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.toString().equals(code)){
            //3. 不一致，报错
            return Result.fail("验证码错误");
        }

        //4.一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();

        //5. 判断用户是否存在
        if (user == null){
            //6. 不存在，创建新用户
            user = createUserWithPhone(phone);
        }

        //7.保存用户信息到session
        session.setAttribute("user",BeanUtil.copyProperties(user,UserDTO.class));
        return Result.ok();
    }



    private User createUserWithPhone(String phone) {
        // 1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 2.保存用户
        save(user);
        return user;
    }
}
