package com.hmdp.utils;

/**
 * 存入到redis中的key值， 常量
 */
public class RedisConstants {

    /**
     *  向redis存入验证码的前缀
     */
    public static final String LOGIN_USER_CODE ="login:code:";
//    public static final String LOGIN_CODE_KEY = "login:code:";

    /**
     * 验证码的存在时间
     */
    public static final Long LOGIN_CODE_TTL = 2L;

    /**
     * 存入token的前缀
     */
    public static final String LOGIN_USER_KEY = "login:token:";

    /**
     * 令牌的存在时间
     */
    public static final Long LOGIN_USER_TTL = 30L;

    /**
     * 请求的头信息
     */
    public static final String LOGIN_AUTHORIZATION = "authorization";



    /**
     *  商店在redis当中的存在时间
     */
    public static final Long  SHOP_TTL = 30L;

    /**
     *  null商店在redis当中的存在时间
     */
    public static final Long  NULL_SHOP_TTL = 2L;

    /**
     * shopType的前缀
     */
    public static final String SHOP_TYPE_KEY = "shopType:";

    /**
     * redis缓存，保证秒杀券一人一张的key
     */
    public  static final String CACHE_VOUCHER_USER_KEY = "cache:voucher:user:key";
    /**
     * 上述信息的存储时长
     */
    public static final Long CACHE_VOUCHER_USER_TTL = 2L;

    /**
     *
     */

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;



    /**
     * 向缓存存入秒杀券的前缀信息
     */
    public static final String SECKILLVOUCHER_STOCK_HASH = "seckillvoucher:stock";

    /**
     * 向缓存存入购买者的前缀信息
     */
    public static final String SECKILLVOUCHER_ORDER_LIST = "seckillvoucher:order:";

    /**
     * 向redis存入的购买信息  ， 异步获取
     */
    public static final String UNPROCESSED_ORDER = "unprocessed:orders:";


    /**
     *  达人探店  点赞功能
     *      keypoint  1、存储每个表是否需要更新  Hash
     *                2、存储每个表的点赞数量    Hash
     *                3、存储每个表的点赞人的ID  Set
     */
    public static final String BLOG_LIKE_CACHE = "blog:like:";
    public static final String BLOG_LIKE_ISUPDATE = "blog:like:isupdate";
    public static final String BLOG_LIKE_TOTAL = "blog:like:total";
    /**
     * 用户的关注表  set
     */
    public static final String USER_FOLLOW = "user:follow:";
    public static final String FEED_OUT = "FEED:";



    /**
     * 商店shop的前缀
     */
    public static final String SHOP_KEY = "shop:id:";
    /**
     * 已缓存的商店信息的id
     */
    public static final String SHOP_CACHE_ID = "shop:cache:id";

    /**
     * 突然到访的商店热点信息
     */
    public static final String SHOP_SUDDEN_TIMES = "shop:sudden:times";
    /**
     * 对于突然到访的热点数据上锁处理
     */
    public static final String SHOP_CACHING_LOCK = "shop:caching:lock:";

    /**
     * 访问商店的频率统计
     */
    public static final String SHOP_GET_TIMES = "shop:get:times";

    /**
     * 热点数据的访问时间戳
     */
    public static final String HOT_SHOP_TIME = "shop:hot:timeStamp";

    /**
     * 商店更新时间戳的依据
     */
    public static final String HOT_SHOP_UPDATE_KEY = "shop:update:timeStamp";
}
