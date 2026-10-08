package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    /**
     * 开始时间戳
     */
    private static final long BEGIN_TIMESTAMP=1640995200L;
    /**
     * 序列号位
     */
    private static final int COUNT_BITS=32;
    private StringRedisTemplate stringRedisTemplate;
    public RedisIdWorker(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate=stringRedisTemplate;
    }
    public long nextId(String keyPrefix){
        LocalDateTime now=LocalDateTime.now();
        long nowSecond= now.toEpochSecond(ZoneOffset.UTC);
        //获取时差
        long timestamp=nowSecond-BEGIN_TIMESTAMP;
        //生成序列号
        String date=now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //自增
        long count=stringRedisTemplate.opsForValue().increment("icr:"+keyPrefix+":"+date);
        //拼接返回
        //<<向左移动COUNT_BITS
        //|按位取或
        return timestamp<<COUNT_BITS | count;
    }

    public static void main(String[] args) {
        LocalDateTime time=LocalDateTime.of(2022,1,1,0,0,0);
        //根据 UTC 规则生成秒数
        long second = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println("second = "+second);
    }
}
