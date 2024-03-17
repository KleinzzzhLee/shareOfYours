package com.hmdp.utils;


import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 利用雪花算法 生出不重复且较为复杂的主键
 */
@Component
public class SnowflakeIdWorker {

    public static final Long ORDER_PREFIX = 123L;


    /**
     * 开始时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1709805162L;

    /**
     * 序列号的位数
     *  因为本次项目不是分布式的， 所以无需机器号， 序列号直接12位
     *  不使用机器号
     */
    private static final int COUNT_BITS = 12;

    // 时间戳的位数
    private static final int TIMESTAMP_BITS = 31;



    /**
     * 支持的最大序列号
     */
    private static final long MAX_COUNT = ~(-1 << COUNT_BITS);

    /**
     * 上一次的时间：
     * @return
     */
    private static  long lastTimeStamp = BEGIN_TIMESTAMP;

    /**
     * 序列号
     * @return
     */
    private static long id = 0L;

    /**
     *  获取下一个主键值 通过prefix进行区分不同表的主键
     * @param prefix 主键前缀， 用来区分不同表
     * @return
     */
    public synchronized static Long nextId(Long prefix) {
        LocalDateTime localDateTime = LocalDateTime.now();
        long timeStamp = localDateTime.toInstant(ZoneOffset.MIN).getEpochSecond();

        if(lastTimeStamp == timeStamp) {
            id = (id + 1) & MAX_COUNT;
            if(id == 0) {
                 lastTimeStamp = getNextTime();
            }
        } else {
            id = 0L;
            lastTimeStamp = timeStamp;
        }

//        System.out.println("id = " + id);
        return prefix << (COUNT_BITS + TIMESTAMP_BITS) | ((lastTimeStamp) << COUNT_BITS) | id;
    }

    /**
     * 获得下一个微秒数
     * @return
     */
    private static long getNextTime() {
        long now = LocalDateTime.now().toInstant(ZoneOffset.MIN).getEpochSecond();

        while(now <= lastTimeStamp) {
            now = LocalDateTime.now().toInstant(ZoneOffset.MIN).getEpochSecond();
        }
        return now;
    }

}
