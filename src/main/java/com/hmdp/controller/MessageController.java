package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IMessageService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 * 消息通知 前端控制器
 * </p>
 */
@RestController
@RequestMapping("/message")
public class MessageController {

    @Resource
    private IMessageService messageService;

    /**
     * 我收到的消息列表（倒序分页）
     */
    @GetMapping("/list")
    public Result list(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return messageService.queryMyMessages(current);
    }

    /**
     * 未读消息数，底部导航的红点用。
     * 这个接口在 MvcConfig 里放行了登录校验：它被每个页面的底部导航调用，
     * 未登录时返回 0 比返回 401 更合适，否则公开页面会被跳转到登录页
     */
    @GetMapping("/unread/count")
    public Result unreadCount() {
        return messageService.queryUnreadCount();
    }

    /**
     * 把我的消息全部标记为已读（进入消息页时调用，红点消失）
     */
    @PostMapping("/read")
    public Result readAll() {
        return messageService.readAll();
    }
}
