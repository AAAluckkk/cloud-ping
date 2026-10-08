package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.BlogComments;
import com.hmdp.entity.Follow;
import com.hmdp.entity.Message;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogCommentsService;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IMessageService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Autowired
    private IBlogService blogService;
    @Autowired
    private IUserService userService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private IFollowService followService;
    @Autowired
    private IBlogCommentsService blogCommentsService;
    @Autowired
    private IMessageService messageService;
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog=getById(id);
        if(blog==null){
            return Result.fail("笔记不存在");
        }
        queryBlogUser(blog);
        //查询是否点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        //获取用户
        UserDTO user = UserHolder.getUser();
        if(user==null){
            //未登录，不查询点赞状态（/blog/** 已放行，允许匿名浏览）
            return;
        }
        //判断用户是否点赞
        String key="blog:liked:"+blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, user.getId().toString());
        blog.setIsLike(score!=null);
    }

    @Override
    public Result likeBlog(Long id) {
        //获取用户。/blog/** 是放行的（游客可匿名浏览笔记），点赞必须自己判登录
        UserDTO current = UserHolder.getUser();
        if (current == null) {
            return Result.fail("请先登录");
        }
        Long userId = current.getId();
        //判断用户是否点赞
        String key="blog:liked:"+id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score==null){
            //如果未点赞，点赞
            //数据库点赞数加一
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if(isSuccess){
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
                //给笔记作者发一条"点赞了你的笔记"的消息。
                //send 内部会过滤掉"自己赞自己"，这里不用再判
                Blog blog = getById(id);
                if (blog != null) {
                    messageService.send(blog.getUserId(), userId, Message.TYPE_LIKE, id);
                }
            }
        }else{
            //已经点赞就取消
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess){
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key= RedisConstants.BLOG_LIKED_KEY+id;
        //查询top5 点赞用户 zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5==null||top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //解析出用户 id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String strid= StrUtil.join(",",ids);
        //根据 id查用户 手动拼接sql语句再用stream流解析
        List<UserDTO> users = userService
                .query()
                .in("id", ids).last("order by field(id," + strid + ")")
                .list()
                .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户。发笔记必须登录，这里不能假设 UserHolder 里一定有值
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean b = save(blog);
        if (!b){
            return Result.fail("新增笔记失败");
        }
        //查询笔记作者的粉丝 select * from tb_follow where follow_user_id=?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        //推送笔记到所有粉丝
        for(Follow follow:follows){
            Long userId = follow.getUserId();
            String key="feed:"+userId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //获取当前用户。收件箱是私人的，必须登录
        UserDTO current = UserHolder.getUser();
        if (current == null) {
            return Result.fail("请先登录");
        }
        Long userId = current.getId();
        //查询收件箱 ZREVRANGEBYSCORE key Max Min LIMIT offset count
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(RedisConstants.FEED_KEY + userId, 0, max, offset, 2);
        if (typedTuples==null||typedTuples.isEmpty()) return Result.ok();
        // 4.解析数据：blogId、minTime（时间戳）、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime =0;
        int os=1;//记录相同且最小的时间戳数量
        for (ZSetOperations.TypedTuple<String> typedTuple:typedTuples){
            //获取id
            ids.add(Long.valueOf(typedTuple.getValue()));
            //获取时间戳
            long time = typedTuple.getScore().longValue();
            if(time == minTime){
                os++;
            }else{
                os=1;
                minTime=time;
            }
        }
        // 5.根据id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        for (Blog blog : blogs) {
            // 5.1.查询blog有关的用户
            queryBlogUser(blog);
            // 5.2.查询blog是否被点赞
            isBlogLiked(blog);
        }
        // 6.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);
        return Result.ok(r);
    }

    @Override
    @Transactional
    public Result deleteBlog(Long id) {
        //获取当前登录用户。注意 /blog/** 在 MvcConfig 里是放行的（为了允许匿名浏览博客），
        //所以这里不能假设一定有登录用户，必须自己判空，否则会 NPE
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        //查笔记，不存在就没得删
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        //归属校验：只能删自己发的笔记
        if (!blog.getUserId().equals(user.getId())) {
            return Result.fail("无权删除他人的笔记");
        }
        //删掉笔记下的评论（评论表目前是空的，但保持数据一致性，将来接上评论功能也不用改）
        blogCommentsService.remove(new QueryWrapper<BlogComments>().eq("blog_id", id));
        //删掉笔记本身
        removeById(id);
        //删掉点赞缓存 blog:liked:{id}
        stringRedisTemplate.delete(RedisConstants.BLOG_LIKED_KEY + id);
        //把这篇笔记从所有粉丝的收件箱 feed:{粉丝id} 里摘掉，否则粉丝的滚动分页里会留一条查不出来的空记录
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        for (Follow follow : follows) {
            stringRedisTemplate.opsForZSet()
                    .remove(RedisConstants.FEED_KEY + follow.getUserId(), id.toString());
        }
        //删掉磁盘上的图片
        deleteBlogImages(blog.getImages());
        return Result.ok();
    }

    /**
     * 删除笔记关联的图片文件
     */
    private void deleteBlogImages(String images) {
        if (StrUtil.isBlank(images)) {
            return;
        }
        File baseDir = new File(SystemConstants.IMAGE_UPLOAD_DIR);
        for (String image : images.split(",")) {
            //数据库里存的是 /imgs/blogs/7/14/xxx.jpg 这种 web 路径，
            //去掉 /imgs 前缀才是相对于 nginx 图片根目录的路径
            String relative = StrUtil.removePrefix(image.trim(), "/imgs");
            File file = new File(baseDir, relative);
            try {
                //防目录穿越：images 字段是用户可以随便填的，只允许删图片根目录底下的文件
                if (!file.getCanonicalPath().startsWith(baseDir.getCanonicalPath())) {
                    log.warn("跳过越界的图片路径：{}", image);
                    continue;
                }
            } catch (IOException e) {
                log.warn("解析图片路径失败：{}", image, e);
                continue;
            }
            if (file.isFile()) {
                //这篇笔记已经删了，如果还有别的笔记引用同一张图就不能删文件，否则别人那篇会裂图
                if (count(new QueryWrapper<Blog>().like("images", image)) > 0) {
                    log.info("图片仍被其他笔记引用，跳过删除：{}", image);
                    continue;
                }
                FileUtil.del(file);
            }
        }
    }

    private void queryBlogUser(Blog blog){
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
