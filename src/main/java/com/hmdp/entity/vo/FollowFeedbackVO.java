package com.hmdp.entity.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 关注博主的反馈信息
 */
@Data
public class FollowFeedbackVO implements Serializable {
    /**
     * 用户id
     */
    private Long userId;
    /**
     * 博主id
     */
    private Long authorId;
    /**
     *
     */
    private LocalDateTime time;

}
