package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.Message;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IMessageService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private IUserService userService;
    @Autowired
    private IMessageService messageService;
    @Override
    public Result isFollow(Long followUserId) {
        //获取登陆用户
        Long userId= UserHolder.getUser().getId();
        //查询是否关注 select count(*) from tb_follow where user_id = ? and follow_user_id = ?
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        //count 大于 0 说明已经关注了
        return Result.ok(count > 0);
    }

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //获取登陆用户
        Long userId= UserHolder.getUser().getId();
        String key="follow:"+userId;
        //判断点赞的状态
        if(isFollow){
            //先看是不是已经关注过了。原来这里不判重，重复点两次会插两条关系、
            //跟着也会发两条重复的关注消息
            int exists = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
            if (exists > 0) {
                return Result.ok();
            }
            //关注，新增数据
            Follow follow=new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean b = save(follow);
            if (b){
                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
                //给对方发一条"关注了你"的消息
                messageService.send(followUserId, userId, Message.TYPE_FOLLOW, null);
            }
        }else{
            //取关，删除数据delete from tb_follow where user_id =? and follow_user_id=?
            boolean b = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));
            if(b){
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result followCommons(Long id) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        String key="follow:";
        //获取交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key + userId, key + id);
        if(intersect==null||intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }

    @Override
    public Result queryFans(Integer current) {
        // 粉丝 = tb_follow 里 follow_user_id 是我的人
        return queryFollowList(current, "follow_user_id");
    }

    @Override
    public Result queryFollowees(Integer current) {
        // 我关注的人 = tb_follow 里 user_id 是我的人
        return queryFollowList(current, "user_id");
    }

    /**
     * 查关注关系列表的公共逻辑。
     * column 传 "follow_user_id" 查粉丝，传 "user_id" 查我关注了谁。
     */
    private Result queryFollowList(Integer current, String column) {
        UserDTO me = UserHolder.getUser();
        if (me == null) {
            return Result.fail("请先登录");
        }
        Page<Follow> page = query()
                .eq(column, me.getId())
                //最近关注的 / 最近来的粉丝排前面
                .orderByDesc("create_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));

        //查粉丝列表要取关系行里的 userId（关注我的人），
        //查关注列表要取 followUserId（我关注的人），两者在表里是相反的两列
        boolean isFans = "follow_user_id".equals(column);
        List<Long> ids = page.getRecords().stream()
                .map(f -> isFans ? f.getUserId() : f.getFollowUserId())
                .collect(Collectors.toList());

        //总数单独返回：标签页上的"(N)"要用真实总数，不能拿当前页的条数充数
        return Result.ok(toUserDTOList(ids), page.getTotal());
    }

    /**
     * 按传入的 id 顺序查用户，用来保住"最近在前"这个排序
     */
    private List<UserDTO> toUserDTOList(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        String idStr = StrUtil.join(",", ids);
        return userService.query()
                .in("id", ids)
                .last("order by field(id," + idStr + ")")
                .list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
    }
}
