package com.hmdp.entity.dto;

import com.hmdp.utils.enums.FeedbackType;
import lombok.Data;

import java.io.Serializable;

/**
 * 关注、点赞、评论的消息通知
 */
@Data
public class FeedbackMessageDTO implements Serializable {
    private FeedbackType type;
    private Object data;
}
