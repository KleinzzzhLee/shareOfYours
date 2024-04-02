package com.hmdp.utils;

public class RabbitConstants {
    //   kp 以下为MQ的常量


    /**
     * routing key 的前缀
     */
    public static final String PREFIX_ROUTING_KEY = "RoutingKey.";
    public static final String ACCEPT_QUEUE = ".queue.";



    // kp MQ的消息队列
    // 判断用户是否为活跃用户所需
    public static final String CHECK_ACTIVE_USER_QUEUE = "check.active.queue";
    // kp 交换机的名称
    public static final String BLOG_EXCHANGE = "blog.exchange.";
    // kp 集中所有推送的交换机
    public static final String CONCENTRATED_EXCHANGE = "concentrated.exchange.";
    // kp 集中所有推送的队列
    public static final String CONCENTRATED_QUEUE = "concentrated.queue.";

    //  kp 反馈交换机
    public static final String FEEDBACK_EXCHANGE_PREFIX = "feedback.like.exchange.";

    // kp 反馈队列
    public static final String FEEDBACK_QUEUE_PREFIX = "feedback.like.queue.";

    // kp 路由键， 在后面拼接 博主id
    public static final String FEEDBACK_ROUTING_KEY_PREFIX = "feedback.routing.key.";
    // kp 接受反馈消息的等待时间
    public static final Long FEEDBACK_TIMEOUT =  1000L;
}
