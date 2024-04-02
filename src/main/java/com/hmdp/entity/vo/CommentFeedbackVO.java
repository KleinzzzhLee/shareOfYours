package com.hmdp.entity.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 评论的反馈信息
 */
@Data
public class CommentFeedbackVO implements Serializable {
    /**
     * 博客id
     */
    private Long blogId;
    /**
     * 用户id
     */
    private Long userId;
    /**
     * 博主id
     */
    private Long authorId;
    /**
     * 评论id
     */
    private Long commentId;
    /**
     * 父级评论id
     */
    private Long parentId;
    /**
     * 评论内容
     */
    private String content;
    /**
     * 时间
     */
    private LocalDateTime time;

}
