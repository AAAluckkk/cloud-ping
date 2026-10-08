package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.ReTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //注册拦截器 排除不拦截的路径
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/user/code",
                        "/user/login",
                        "/shop/**",
                        "/voucher/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/blog/**",
                        // "/error" 必须放行！
                        // 请求出任何错（缺参数、类型不匹配、404……）时，Spring 会把它
                        // 内部转发到 /error。如果这里不放行，拦截器会对着 /error 跑一遍，
                        // 发现没登录就把真正的错误状态码盖成 401，
                        // 前端 common.js 看到 401 又会跳登录页，用户就被莫名踢出去了
                        "/error",
                        // 底部导航的未读红点在每个页面都会调它。放行，
                        // 未登录时接口返回 0，避免公开页面因 401 被弹去登录页
                        "/message/unread/count"
                ).order(1);
        registry.addInterceptor(new ReTokenInterceptor(stringRedisTemplate)).addPathPatterns("/**").order(0);
    }
}
