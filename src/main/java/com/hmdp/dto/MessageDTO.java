package com.hmdp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息列表返回给前端的结构。
 * 这里把"触发者"和"笔记"的信息平铺进来，都是逻辑关联：
 * 表之间不建外键，关联字段只存 id，具体信息由 Service 层批量查出来填。
 */
@Data
public class MessageDTO {

    private Long id;

    /**
     * 消息类型：1关注，2点赞
     */
    private Integer type;

    /**
     * 关联的笔记id，点赞消息才有
     */
    private Long blogId;

    /**
     * 笔记标题，点赞消息才有（用来显示"点赞了你的笔记《xxx》"）
     */
    private String blogTitle;

    /**
     * 是否已读。
     * 显式指定 JSON 名字：Lombok 对 isRead 这种字段生成的是 getIsRead()，
     * 不同 Jackson 版本推导出的属性名可能是 isRead 也可能是 read，写死更稳
     */
    @JsonProperty("isRead")
    private Boolean isRead;

    private LocalDateTime createTime;

    /**
     * 触发这条消息的用户id
     */
    private Long fromUserId;

    /**
     * 触发者的昵称（逻辑关联查出来的，不是表里的字段）
     */
    private String fromUserName;

    /**
     * 触发者的头像（同上）
     */
    private String fromUserIcon;
}
