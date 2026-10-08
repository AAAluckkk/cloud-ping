package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Message;

/**
 * <p>
 * 消息通知 服务类
 * </p>
 */
public interface IMessageService extends IService<Message> {

    /**
     * 发一条消息给对方。
     * 给自己做的操作（自己关注自己、自己赞自己）不会被记录。
     *
     * @param toUserId   接收者
     * @param fromUserId 触发者
     * @param type       {@link Message#TYPE_FOLLOW} / {@link Message#TYPE_LIKE}
     * @param blogId     关联笔记id，关注类消息传 null
     */
    void send(Long toUserId, Long fromUserId, Integer type, Long blogId);

    /**
     * 查我收到的消息（倒序分页）
     */
    Result queryMyMessages(Integer current);

    /**
     * 查我的未读消息数（底部导航红点用）
     */
    Result queryUnreadCount();

    /**
     * 把我的消息全部标为已读
     */
    Result readAll();
}
