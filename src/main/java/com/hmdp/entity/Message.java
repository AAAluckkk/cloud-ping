package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 消息通知。别人关注你、点赞你的笔记时，给对方生成一条
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_message")
public class Message implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 消息类型：关注
     */
    public static final int TYPE_FOLLOW = 1;
    /**
     * 消息类型：点赞
     */
    public static final int TYPE_LIKE = 2;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 接收者用户id（消息是给谁的）
     */
    private Long userId;

    /**
     * 触发这条消息的用户id（谁关注/点赞了你）
     */
    private Long fromUserId;

    /**
     * 消息类型：1关注，2点赞
     */
    private Integer type;

    /**
     * 关联的笔记id，点赞消息有值，关注消息为 null
     */
    private Long blogId;

    /**
     * 是否已读：false未读，true已读。
     * 字段名 isRead 靠默认的驼峰转换会变成 is_read，
     * 但 MyBatis-Plus 对 isXxx 这种字段名有过歧义，这里显式写清楚列名
     */
    @TableField("is_read")
    private Boolean isRead;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
