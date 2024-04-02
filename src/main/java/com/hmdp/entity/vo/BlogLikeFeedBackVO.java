package com.hmdp.entity.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 点赞博客的反馈信息
 */
@Data
public class BlogLikeFeedBackVO implements Serializable {
    /**
     * 博客id
     */
    private Long blogId;
    /**
     * 博客标题
     */
    private String blogName;
    /**
     * 用户id
     */
    private Long userId;
    /**
     * 博主id
     */
    private Long authorId;
    /**
     * 时间
     */
    private LocalDateTime time;
}
