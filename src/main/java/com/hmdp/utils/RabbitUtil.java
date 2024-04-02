package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Blog;
import com.hmdp.entity.dto.FeedbackMessageDTO;
import com.hmdp.utils.enums.ExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.hmdp.utils.RabbitConstants.*;

@Slf4j
@Component
public class RabbitUtil {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    /**
     *  kp  自动删除
     *      交换机  没有队列进行绑定时。 自动删除
     *      消息队列  没有消费者自动删除
     */
    @Resource
    RabbitAdmin rabbitAdmin;

    @Resource
    AmqpAdmin amqpAdmin;

    @Resource
    RabbitTemplate rabbitTemplate;

    @Resource
    RabbitTemplate rabbitTemplateACK;

    @Resource
    ConnectionFactory connectionFactory;


    /**
     * kp 判断是否为活跃用户
     */
    @RabbitListener(queues = CHECK_ACTIVE_USER_QUEUE)
    public void checkActiveUser(String userId) {
        // 判断是否签到过， 签到，直接返回
        log.debug("进入签到方法");
        int dayOfWeek = LocalDateTime.now().getDayOfWeek().getValue();
        Boolean isSign = redisTemplate.opsForValue().getBit(RedisConstants.USER_SIGN_PREFIX + userId,dayOfWeek- 1);
        if(BooleanUtil.isTrue(isSign)) {
            log.debug("已签到");
            return;
        }
        // 1、签到  + 统计签到次数
        int signTimesNow = userSign(Long.valueOf(userId));
        // 2、 todo 还原 判断是否为活跃用户，是 直接返回
        Boolean isMember = redisTemplate.opsForSet().isMember(RedisConstants.USER_ACTIVE_LIST, userId);
        if(BooleanUtil.isFalse(isMember)) {
//        if(true) {
            // 不是活跃用户
            // 3、获取上周期的签到次数
            String tt = (String) redisTemplate.opsForHash().get(RedisConstants.USER_SIGN_TIMES_LAST, userId);
            int signTimesLast = (tt == null) ? 0 : Integer.parseInt(tt);
            // 4、判断是否升级
            if(signTimesNow > 3 || signTimesLast > 3 ) {
                // 5、升级为活跃用户，在缓存中记录
                redisTemplate.opsForSet().add(RedisConstants.USER_ACTIVE_LIST, userId);
                // 7、为其创建相关队列，并实现绑定
                List<String> authorIds = redisTemplate.opsForSet().members(RedisConstants.USER_FOLLOW + userId)
                        .stream()
                        .map(Object::toString).collect(Collectors.toList());
                createQueue(userId, authorIds);
            }
        }
    }
    private int userSign(Long userId) {
        // 1、获取当前用户及日期
        int dayOfWeek = LocalDateTime.now().getDayOfWeek().getValue();
        // 2、在缓存中更新签到记录bitmap
        List<Object> objects = redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public <K, V> Object execute(RedisOperations<K, V> operations) throws DataAccessException {
                redisTemplate.opsForValue().setBit(RedisConstants.USER_SIGN_PREFIX + userId, dayOfWeek - 1, true);
                redisTemplate.opsForValue().bitField(RedisConstants.USER_SIGN_PREFIX + userId,
                        BitFieldSubCommands.create()
                                .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfWeek)).valueAt(0));
                return null;
            }
        });

        List<Long> list = (List<Long>) objects.get(1);
        Long num = list.get(0);
        // 计算最大签到次数
        int max = 0;
        int temp = 0;
        while(num != 0) {
            if((num & 1) == 1) {
                temp++;
            } else {
                temp = 0;
            }
            num = num >>> 1;
            max = Math.max(max, temp);
        }
        redisTemplate.opsForHash().put(RedisConstants.USER_SING_TIMES_NOW, userId.toString(), String.valueOf(max));
        return max;
    }
    /**
     * 创建交换机
     * @param exchangeName 交换机名字
     * @param isDurable
     * @return
     */
    public void createExchange(String exchangeName, ExchangeType exchangeType, boolean isDurable, Map<String,Object> arguments) {
        Exchange exchange = null;
        if(exchangeType == ExchangeType.DIRECT_EXCHANGE) {
            exchange = new DirectExchange(exchangeName, isDurable, false, arguments);
        } else if (exchangeType == ExchangeType.FANOUT_EXCHANGE) {
            exchange = new FanoutExchange(exchangeName, isDurable, false, arguments);
        } else if (exchangeType == ExchangeType.TOPIC_EXCHANGE) {
            exchange = new TopicExchange(exchangeName, isDurable, false, arguments);
        }
        rabbitAdmin.declareExchange(exchange);
    }
    /**
     * 创建队列、建立绑定关系，exchangeName为null， 只建立队列
     * @param queueName 队列名称
     * @param exchangeName 交换机名称
     * @param routingKey 路由键
     * @param isDurable  队列持久化
     * @param isAutoDelete 队列是否自动删除
     * @param queueArguments 队列参数
     * @param bindingArguments 绑定参数
     */
    public void createQueueAndBinding(String queueName, String exchangeName, String routingKey,
                                      boolean isDurable, boolean isAutoDelete,
                                      Map<String, Object> queueArguments, Map<String,Object> bindingArguments) {
        // 创建队列
        rabbitAdmin.declareQueue(new Queue(queueName, isDurable, false, isAutoDelete, queueArguments));
        // 简历绑定关系
        if(!StringUtils.isEmpty(exchangeName)) {
            rabbitAdmin.declareBinding(new Binding(queueName,
                    Binding.DestinationType.QUEUE,
                    exchangeName,
                    routingKey,
                    bindingArguments));
        }
    }

    /**
     * 创建多条队列到一台交换机的绑定关系
     *  kp 中继模式(队列链模式)
     *      开启 生产者ack 和 消费者ack
     * @param queues  队列集合
     * @param exchangeName 交换机
     * @param routingKey 路由键
     */
    private void bindingFromQueueToExchange(List<String> queues, String exchangeName,String routingKey) {
        // kp  SimpleMessageListenerContainer 是为指定的队列开启 消费者确认
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        String[] queueNames = queues.toArray(new String[0]);
        container.addQueueNames(queueNames);
        // kp 开启消费者确认
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
//        MessageListener messageListener = message -> {
//            Blog blog = (Blog) getInfoFromMessageBody(message, Blog.class);
//            rabbitTemplateACK.convertAndSend(exchangeName, routingKey, JSONUtil.toJsonStr(blog));
////            log.debug("实现了消息的汇总！！！！！！");
//        };
        // kp 中继模式  开启生产者确认
        container.setMessageListener(new ChannelAwareMessageListener() {
            @Override
            public void onMessage(Message message, Channel channel) throws Exception {
                Blog blog = JSONUtil.toBean(getInfoFromMessageBody(message,null), Blog.class);
                // rabbitTemplateACK 开启了生产者确认， todo 但没有 开启重试机制
                rabbitTemplateACK.convertAndSend(exchangeName, routingKey, JSONUtil.toJsonStr(blog));
                // kp 方法参数
                //      1、deliverTag  此参数是消息的唯一标识符，由 RabbitMQ 服务器在发布消息时生成，
                //                      标识了一个特定的消息实例。每一个消息送达消费者时都会附带一个 deliveryTag，
                //                      这是一个长整型的数值，通常消息被发送的顺序决定该值的大小
                //      2、multiple   表示是否一次确认多条消息。如果此参数为
                //                     true，则会确认所有 deliveryTag 小于或等于传入值的消息；
                channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
            }
        });
        container.start();
    }


    /**
     * 为活跃用户创建消息队列
     * 将为用户创建的队列
     *
     * @param userId    用户id
     * @param followIds 博主们的id
     */
    public void createQueue(String userId, List<String> followIds) {
        String routingKey = userId;
        List<String> queueIds = followIds.stream()
                .map( (e) -> {
                    return  userId + ACCEPT_QUEUE + e;
                })
                .collect(Collectors.toList());
        String concentratedExchangeName = CONCENTRATED_EXCHANGE + Long.parseLong(userId) % 5;
        // kp 根据用户id 取余 创建汇总交换机， 防止new
        rabbitAdmin.declareExchange(new TopicExchange(concentratedExchangeName, true, false, null));
        // kp 创佳汇总队列 ， 将所有关注博主的推送集中在此
        String concentratedQueueName = CONCENTRATED_QUEUE + userId;
        rabbitAdmin.declareQueue(new Queue(concentratedQueueName, true, false, false, null));
        // kp 将汇总队列和汇总交换机绑定  绑定键为 用户id
        rabbitAdmin.declareBinding(new Binding(concentratedQueueName,
                Binding.DestinationType.QUEUE,
                concentratedExchangeName,
                routingKey, null));
        // 创建消息路由队列
        for(int i = 0; i < queueIds.size(); i++) {
            String queue = queueIds.get(i);
            String exchangeName = BLOG_EXCHANGE + Long.parseLong(followIds.get(i))%10;
            createExchange(exchangeName, ExchangeType.TOPIC_EXCHANGE, true, null);
            rabbitAdmin.declareQueue(new Queue(queue, true, false, false));
            rabbitAdmin.declareBinding(new Binding(queue,
                    Binding.DestinationType.QUEUE,
                    exchangeName,
                    PREFIX_ROUTING_KEY + followIds.get(i), null));
        }
        // kp 将 消息路由队列 和 汇总交换机 建立路由
        bindingFromQueueToExchange(queueIds, concentratedExchangeName, routingKey);
    }


    /**
     * 将Message对象的消息体中JSON字串拆箱
     * @param message
     * @param response
     * @return
     */
    public static String getInfoFromMessageBody(Message message,GetResponse response) {
        if(message != null) {
            String jsonStr = new String(message.getBody());
            String str = jsonStr.replaceAll("\\\\", "");
            String res = str.substring(1, str.length() - 1);
            return res;
        } else {
            String jsonStr = new String(response.getBody());
            String str = jsonStr.replaceAll("\\\\", "");
            String res = str.substring(1, str.length() - 1);
            return res;
        }
    }


    /**
     * 发送评论、点赞、关注的反馈消息
     * @param exchange 交换机
     * @param queue 队列
     * @param message 消息
     * @param routingKey 路由
     */
    public  void sendFeedbackMessage(String exchange, String queue, FeedbackMessageDTO message, String routingKey) {
        // 创建交换机
        createExchange(exchange, ExchangeType.TOPIC_EXCHANGE, true, null);
        // kp 设置参数， 回馈队列设置过期时间
        Map<String, Object> argsQueue = new HashMap<>();
        argsQueue.put("x-expires",7 * 24 * 60 * 60 * 1000);
        createQueueAndBinding(queue, exchange, routingKey, true,
                false, argsQueue, null);
        // 将message的消息解封 查看是哪种 feedback
        rabbitTemplate.convertAndSend(exchange, routingKey, JSONUtil.toJsonStr(message));
//        FeedbackType type = message.getFeedbackType();
//        Object o = message.getData();
//        CommentFeedbackVO comment;
//        FollowFeedbackVO follow;
//        BlogLikeFeedBackVO blogLike;
//        if(type == FeedbackType.isComment) {
//            comment = (CommentFeedbackVO) o;
//            rabbitTemplate.convertAndSend(exchange, routingKey, comment);
//        } else if (type == FeedbackType.isFollow) {
//            follow = (FollowFeedbackVO) o;
//            rabbitTemplate.convertAndSend(exchange, routingKey, follow);
//        } else if(type == FeedbackType.isBlogLike) {
//            blogLike = (BlogLikeFeedBackVO) o;
//            rabbitTemplate.convertAndSend(exchange, routingKey, blogLike);
//        }
    }

    // 开启消费者确认
   public List<String> getMessageACK(String queueName) {
       List<String> res = rabbitTemplate.execute(new ChannelCallback<List<String>>() {
           @Override
           public List<String> doInRabbit(Channel channel) throws Exception {
               List<String> res = new ArrayList<>();
               while (true) {
                   GetResponse message = channel.basicGet(queueName, false);
                   if (message != null) {
                       res.add(getInfoFromMessageBody(null, message));
                       channel.basicAck(message.getEnvelope().getDeliveryTag(), false);
                   } else {
                       break;
                   }
               }
               return res;
           }
       });
       return res;
   }


}
