package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.dto.UserProfileDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserInfoService userInfoService;
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验号码
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号长度必须为11位");
        }
        //符合生成验证码 6位随机数字
        String code= RandomUtil.randomNumbers(6);
        //把验证码保存到 redis里 设置有效期为2分钟
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        log.info("验证码发送成功{}",code);

        return Result.ok(code);
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //校验号码
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号长度必须为11位");
        }
        //校验验证码
        Object cacheCode=stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        String code= loginForm.getCode();
        //不一致报错
        if(cacheCode==null||!cacheCode.toString().equals(code)){
            return Result.fail("验证码错误");
        }
        //一致根据手机号查询用户
        //query() 代表select * from tb_user
        //eq()代表 where phone = ?
        //one()代表取一个
        User user=query().eq("phone",phone).one();
        //判断用户是否存在
        if(user==null){
            //不存在 创建新用户
            user=createUserWithPhone(phone);
        }
        //存储 UserDTO 节省空间
        String token= UUID.randomUUID().toString(true);
        UserDTO userDTO=BeanUtil.copyProperties(user,UserDTO.class);
        Map<String,Object> userMap=BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString()));

        //设置redis存储方式
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY+token,userMap);
        //设置令牌过期时间
        stringRedisTemplate.expire(LOGIN_USER_KEY+token,LOGIN_USER_TTL,TimeUnit.MINUTES);
        return Result.ok(token);
    }

    @Override
    public Result logout(String token) {
        //删除 redis 里的登录令牌，令牌一没了，后续请求就查不到用户，会被拦截器挡回登录页
        if (StrUtil.isNotBlank(token)) {
            stringRedisTemplate.delete(LOGIN_USER_KEY + token);
        }
        return Result.ok();
    }

    @Override
    @Transactional
    public Result updateProfile(UserProfileDTO userProfileDTO, String token) {
        //当前登录用户。UserController 那个类里 /user/** 是要登录的，
        //但这里仍然判一次，避免以后被加到放行路径里时出现 NPE
        UserDTO current = UserHolder.getUser();
        if (current == null) {
            return Result.fail("请先登录");
        }
        Long userId = current.getId();

        //1.昵称和头像都存在 tb_user。
        //   updateById 只更新非 null 字段，所以 new 一个只装要改的字段的 User 就够了
        User user = new User();
        user.setId(userId);
        boolean userChanged = false;

        if (StrUtil.isNotBlank(userProfileDTO.getNickName())) {
            String nickName = userProfileDTO.getNickName().trim();
            if (nickName.length() > 12) {
                //tb_user.nick_name 是 varchar(32)，这里按中文场景收得更紧一点
                return Result.fail("昵称不能超过12个字");
            }
            user.setNickName(nickName);
            userChanged = true;
        }

        //换头像前先把旧头像的路径记下来，更新成功后要删掉旧文件
        String oldIcon = null;
        if (StrUtil.isNotBlank(userProfileDTO.getIcon())) {
            User before = getById(userId);
            oldIcon = before == null ? null : before.getIcon();
            user.setIcon(userProfileDTO.getIcon().trim());
            userChanged = true;
        }

        if (userChanged) {
            updateById(user);
            //关键：登录态的用户信息是存在 Redis 的 login:token:{token} 里的，
            ///user/me 等接口读的是这份副本（由 ReTokenInterceptor 装进 UserHolder），
            //不一起更新的话，数据库改了页面上也看不到
            if (StrUtil.isNotBlank(token)) {
                String redisKey = LOGIN_USER_KEY + token;
                if (user.getNickName() != null) {
                    stringRedisTemplate.opsForHash().put(redisKey, "nickName", user.getNickName());
                }
                if (user.getIcon() != null) {
                    stringRedisTemplate.opsForHash().put(redisKey, "icon", user.getIcon());
                }
            }
        }

        //删掉被替换下来的旧头像文件
        if (oldIcon != null && !oldIcon.equals(user.getIcon())) {
            deleteOldIcon(oldIcon);
        }

        //2.其余字段存在 tb_user_info。
        //   这张表可能还没有这个用户的记录（表里默认是空的），
        //   所以统一走 saveOrUpdate：查不到就插入，查到了就更新
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            info = new UserInfo();
            info.setUserId(userId);
        }
        if (StrUtil.isNotBlank(userProfileDTO.getIntroduce())) {
            String introduce = userProfileDTO.getIntroduce().trim();
            if (introduce.length() > 128) {
                //tb_user_info.introduce 是 varchar(128)
                return Result.fail("个人介绍不能超过128个字");
            }
            info.setIntroduce(introduce);
        }
        //gender 是 Boolean，false 表示"男"，是有意义的取值，所以只能用 != null 判断，
        //不能像字符串那样用 isNotBlank / isEmpty，否则选"男"会被当成没填
        if (userProfileDTO.getGender() != null) {
            info.setGender(userProfileDTO.getGender());
        }
        if (StrUtil.isNotBlank(userProfileDTO.getCity())) {
            info.setCity(userProfileDTO.getCity().trim());
        }
        if (userProfileDTO.getBirthday() != null) {
            info.setBirthday(userProfileDTO.getBirthday());
        }
        userInfoService.saveOrUpdate(info);

        return Result.ok();
    }

    /**
     * 删除被替换下来的旧头像文件。
     * 只删我们自己上传到 icons 目录、且已经没人引用的图；
     * 内置图标（default-icon.png 之类）往往被多个种子用户共用，靠引用计数挡掉
     */
    private void deleteOldIcon(String oldIcon) {
        if (StrUtil.isBlank(oldIcon) || !oldIcon.startsWith("/imgs/icons/")) {
            return;
        }
        //当前这个用户已经改过 icon 了，如果还能查到引用，说明是别人（或内置图标）在用
        int stillUsed = count(new QueryWrapper<User>().eq("icon", oldIcon));
        if (stillUsed > 0) {
            log.info("旧头像仍被引用，跳过删除：{}", oldIcon);
            return;
        }
        File baseDir = new File(SystemConstants.IMAGE_UPLOAD_DIR);
        //数据库里存的是 /imgs/icons/... 这种 url，去掉 /imgs 前缀才是磁盘相对路径
        File file = new File(baseDir, StrUtil.removePrefix(oldIcon, "/imgs"));
        try {
            //防目录穿越，icon 字段是用户可传的
            if (!file.getCanonicalPath().startsWith(baseDir.getCanonicalPath())) {
                log.warn("跳过越界的头像路径：{}", oldIcon);
                return;
            }
        } catch (IOException e) {
            log.warn("解析头像路径失败：{}", oldIcon, e);
            return;
        }
        if (file.isFile()) {
            FileUtil.del(file);
        }
    }

    private User createUserWithPhone(String phone) {
        User user=new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        //保存用户
        save(user);
        log.info("保存用户{}",user);
        return user;
    }
    @Override
    public Result sign(){
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY+userId+keySuffix;
        int day=now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key,day-1,true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY+userId+keySuffix;
        int day=now.getDayOfMonth();
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(day)).valueAt(0)
        );
        if(result ==  null){
            //没有签到结果
            return Result.ok(0);
        }
        Long num=result.get(0);
        if(num==null||num==0){
            return Result.ok(0);
        }
        int count = 0;
        while(true){
            if((num&1)==0){
                break;
            }else {
                // 如果不为0，说明已签到，计数器+1
                count++;
            }
            // 把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num >>>= 1;
        }
        return Result.ok(count);
    }
}
