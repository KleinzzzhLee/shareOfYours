package com.hmdp.service.impl;

import cn.hutool.Hutool;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author zzzhlee
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private RedisTemplate redisTemplate;

    @Override
    public Result sedCode(String phone, HttpSession session) {
        // todo 前端的工作 1、检验手机号的格式
        if(RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        // 2、生成验证码
        String code = RandomUtil.randomNumbers(6);
        // todo 利用邮箱生成验证码
//        // 3、将验证码存入到session中，
//        session.setAttribute("code", code);

        // 3、将验证码存入到redis中，
        redisTemplate.opsForValue().set(RedisConstants.LOGIN_USER_CODE + phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 4、发送验证码
        log.debug("发送的验证码为=" + code);
        // 5、展示结果
        return Result.ok();
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

        //1. 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }

        //2. 校验验证码
        String cacheCode = (String) redisTemplate.opsForValue().get(RedisConstants.LOGIN_USER_CODE + loginForm.getPhone());
//        Object cacheCode = session.getAttribute("code");
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)){
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

        //7.保存用户信息到redis

        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

        // todo 生成令牌， 用于作为key值
        String token = RandomUtil.randomString(10);
        String userDTOJSON = JSONUtil.toJsonStr(userDTO);
        // 向redis中存入token 设置有效期
        redisTemplate.opsForValue().set(RedisConstants.LOGIN_USER_KEY + token, userDTOJSON, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 删除验证码
        Boolean delete = redisTemplate.delete(RedisConstants.LOGIN_USER_CODE + phone);
        if(Boolean.TRUE.equals(delete)) {
            log.debug("删除验证码成功");
        }

        return Result.ok(token);
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
