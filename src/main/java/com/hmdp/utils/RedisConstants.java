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
     * 商店shop的前缀
     */
    public static final String SHOP_KEY = "shop:id:";

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

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
}
