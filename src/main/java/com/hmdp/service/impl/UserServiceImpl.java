package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.dto.FeedbackMessageDTO;
import com.hmdp.entity.dto.LoginFormDTO;
import com.hmdp.entity.dto.Result;
import com.hmdp.entity.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.vo.MailVO;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.*;
import com.hmdp.utils.enums.FeedbackType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    @Resource
    private MailServiceImpl mailService;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Override
    public Result sendCode(String account) {
        // 1、检验手机号的格式
        if(RegexUtils.isEmailInvalid(account) && RegexUtils.isPhoneInvalid(account)) {
            return Result.fail("账号格式错误");
        }
        // 2、生成验证码
        String code = RandomUtil.randomNumbers(6);

//        // 3、将手机验证码存入到session中，
//        session.setAttribute("code", code);
        // 3、将手机验证码存入到redis中，
        // redisTemplate.opsForValue().set(RedisConstants.LOGIN_USER_CODE + account, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 3、todo 利用邮箱生成验证码
        MailVO mail = new MailVO();
        mail.setTo(account);
        mail.setCode(code);
        // 3.1将验证码存入redis
        redisTemplate.opsForValue().set(RedisConstants.LOGIN_USER_CODE + account, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        if (BooleanUtil.isFalse(mailService.senMail(mail))) {
            log.debug("发送的验证码为=" + code);
            return Result.fail("手机号登录 " + code);
        }

        // 4、发送验证码
        log.debug("发送的验证码为=" + code);
        // 5、展示结果
        return Result.ok();
    }


    /**
     *  用户登录
     * @param loginForm 登录信息
     * @param
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm) {
        // 1、检验手机号
        // 2、检验验证码 或者 检验密码 todo 密码在数据库中存储要采用加密形式
        // todo 检验是否注册
        //1. 校验邮箱
        String account = loginForm.getPhone();
        if (RegexUtils.isEmailInvalid(account) && RegexUtils.isPhoneInvalid(account)) {
            return Result.fail("账户格式错误");
        }
        //2. 校验验证码
        String cacheCode = (String) redisTemplate.opsForValue().get(RedisConstants.LOGIN_USER_CODE + account);
//        Object cacheCode = session.getAttribute("code");
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)){
            //3. 不一致，报错
            return Result.fail("验证码错误");
        }

        //4.一致，根据手机号查询用户
        User user = query().eq("account", account).one();

        //5. 判断用户是否存在
        if (user == null){
            //6. 不存在，创建新用户
            user = createUserWithAccount(account);
        }

        //7.保存用户信息到redis

        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

        // todo 生成令牌， 用于作为key值
        String token = RandomUtil.randomString(6);

        int i = Math.abs(token.hashCode() % 500);

        // 向redis中存入token 设置有效期
        // kp 采用 hash进行存储
        // redisTemplate.opsForValue().set(RedisConstants.LOGIN_USER_KEY + token, userDTOJSON, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        Map<String, String> map = new HashMap<>();
        map.put(token + "." + "nickName", userDTO.getNickName());
        map.put(token + "." + "icon", userDTO.getIcon());
        map.put(token + "." + "id", String.valueOf(userDTO.getId()));
        map.put(token + "." + "timestamp", String.valueOf(System.currentTimeMillis()/1000));
         redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public <K, V> Object execute(RedisOperations<K, V> operations) throws DataAccessException {
                redisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY + i, map);
                redisTemplate.delete(RedisConstants.LOGIN_USER_CODE + account);
                return null;
            }
        });
         // kp 用户签到 利用消息队列异步执行
        rabbitTemplate.convertAndSend(RabbitConstants.CHECK_ACTIVE_USER_QUEUE, user.getId());
//        userSign(user.getId());
        // 删除验证码
//        boolean delete = (boolean) list.get(1);
//        if(Boolean.TRUE.equals(delete)) {
//            log.debug("删除验证码成功");
//        }

        return Result.ok(token);
    }



    private User createUserWithAccount(String account) {
        // 1.创建用户
        User user = new User();
        user.setAccount(account);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 2.保存用户
        save(user);
        return user;
    }



    @Override
    public Result getFeedback() {
        // 获取当前用户
        UserDTO user = UserHolder.getUser();
        Map<FeedbackType, List<Object>> resMap = new HashMap<>();
        resMap.put(FeedbackType.isBlogLike, new ArrayList<>());
        resMap.put(FeedbackType.isComment, new ArrayList<>());
        resMap.put(FeedbackType.isFollow, new ArrayList<>());
        // 从MQ中获取消息
        while(true) {
            Message message = rabbitTemplate.receive(
                    RabbitConstants.FEEDBACK_QUEUE_PREFIX + user.getId() % 10,
                    RabbitConstants.FEEDBACK_TIMEOUT);
            if(message == null) {
                break;
            }
            FeedbackMessageDTO messageDTO =
                    JSONUtil.toBean(RabbitUtil.getInfoFromMessageBody(message, null), FeedbackMessageDTO.class);
            List<Object> list = resMap.get(messageDTO.getType());
            list.add(messageDTO.getData());
        }
        return Result.ok(resMap);
    }





    // kp 用户签到， 采用redis的BitMap 转入 RabbitMQ异步处理
//    public Result userSign(Long userId) {
//        // 1、获取当前用户及日期
//        int dayOfWeek = LocalDateTime.now().getDayOfWeek().getValue();
//        // 2、在缓存中更新签到记录bitmap
//        redisTemplate.opsForValue().setBit(RedisConstants.USER_SIGN_PREFIX + userId, dayOfWeek - 1, true);
//        // 3、统计最大连续签到次数 todo 利用消息队列计算， 存储在redis中
//        List<Long> list =  redisTemplate.opsForValue().bitField(RedisConstants.USER_SIGN_PREFIX + userId,
//                BitFieldSubCommands.create()
//                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfWeek)).valueAt(0));
//
//        Long num = list.get(0);
//        // 计算最大签到次数
//        int max = 0;
//        int temp = 0;
//        while(num != 0) {
//            if((num & 1) == 1) {
//                temp++;
//            } else {
//                temp = 0;
//            }
//            num = num >>> 1;
//            max = Math.max(max, temp);
//        }
//        log.debug("签到次数" + max);
//        return Result.ok();
//    }

}
