package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.MessageDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Message;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.MessageMapper;
import com.hmdp.service.IMessageService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <p>
 * 消息通知 服务实现类
 * </p>
 */
@Service
public class MessageServiceImpl extends ServiceImpl<MessageMapper, Message> implements IMessageService {

    @Resource
    private IUserService userService;

    /**
     * 这里注入 Mapper 而不是 IBlogService：
     * BlogServiceImpl 要反过来调 IMessageService 记点赞消息，
     * 两个 Service 互相注入会形成循环依赖，用 Mapper 可以绕开
     */
    @Resource
    private BlogMapper blogMapper;

    @Override
    public void send(Long toUserId, Long fromUserId, Integer type, Long blogId) {
        //自己对自己做的操作不产生消息（自己关注自己、自己赞自己）
        if (toUserId == null || fromUserId == null || toUserId.equals(fromUserId)) {
            return;
        }
        Message message = new Message()
                .setUserId(toUserId)
                .setFromUserId(fromUserId)
                .setType(type)
                .setBlogId(blogId)
                .setIsRead(false);
        save(message);
    }

    @Override
    public Result queryMyMessages(Integer current) {
        UserDTO me = UserHolder.getUser();
        if (me == null) {
            return Result.fail("请先登录");
        }
        Page<Message> page = query()
                .eq("user_id", me.getId())
                .orderByDesc("create_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        //total 是全部消息数，不是当前页条数
        return Result.ok(toDTOList(page.getRecords()), page.getTotal());
    }

    @Override
    public Result queryUnreadCount() {
        UserDTO me = UserHolder.getUser();
        //这个接口被底部导航调用，未登录时不报错、直接返回 0 更合适，
        //否则每个公开页面都会因为 401 被跳转到登录页
        if (me == null) {
            return Result.ok(0);
        }
        int count = query()
                .eq("user_id", me.getId())
                .eq("is_read", false)
                .count();
        return Result.ok(count);
    }

    @Override
    public Result readAll() {
        UserDTO me = UserHolder.getUser();
        if (me == null) {
            return Result.fail("请先登录");
        }
        update()
                .set("is_read", true)
                .eq("user_id", me.getId())
                .eq("is_read", false)
                .update();
        return Result.ok();
    }

    /**
     * 把消息转成前端要的结构，顺手把"触发者"和"笔记标题"批量查出来填上（逻辑关联）
     */
    private List<MessageDTO> toDTOList(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }
        //批量查触发者，避免每条消息查一次用户
        Set<Long> fromUserIds = messages.stream()
                .map(Message::getFromUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, User> userMap = fromUserIds.isEmpty() ? Collections.emptyMap()
                : userService.listByIds(fromUserIds).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));

        //批量查笔记标题，只有点赞消息带 blogId
        Set<Long> blogIds = messages.stream()
                .map(Message::getBlogId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> blogTitleMap = blogIds.isEmpty() ? Collections.emptyMap()
                : blogMapper.selectBatchIds(blogIds).stream()
                        .collect(Collectors.toMap(Blog::getId, Blog::getTitle));

        return messages.stream().map(m -> {
            MessageDTO dto = new MessageDTO();
            dto.setId(m.getId());
            dto.setType(m.getType());
            dto.setBlogId(m.getBlogId());
            dto.setIsRead(m.getIsRead());
            dto.setCreateTime(m.getCreateTime());
            dto.setFromUserId(m.getFromUserId());
            User from = userMap.get(m.getFromUserId());
            if (from != null) {
                dto.setFromUserName(from.getNickName());
                dto.setFromUserIcon(from.getIcon());
            }
            if (m.getBlogId() != null) {
                dto.setBlogTitle(blogTitleMap.get(m.getBlogId()));
            }
            return dto;
        }).collect(Collectors.toList());
    }
}
