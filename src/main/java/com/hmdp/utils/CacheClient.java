package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * CacaheClient redis的封装类，适用所有情况下的缓存问题
 */
@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //普通的 set 设置过期时间
    public void set(String key,Object value,Long time ,TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(value),time,unit);
    }
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //逻辑过期 set封装
        RedisData redisData=new RedisData();
        redisData.setData(JSONUtil.toJsonStr(value));
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    //解决缓存穿透的方法
    public <R,ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time, TimeUnit unit){
        //判断店铺是否存在 redis 存在直接返回
        String key=keyPrefix+id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json,type);
        }
        //判断是否为空
        if(json!=null){
            return null;
        }
        //不存在从数据库里取
        R r=dbFallback.apply(id);
        if (r==null){
            //存入空值，防止穿透
            stringRedisTemplate.opsForValue().set(key,"",3,TimeUnit.MINUTES);
            return null;
        }
        //存进redis
        this.set(key,r,time,unit);
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);
    //逻辑过期解决缓存击穿
    public <R,ID> R queryWithLogicalExprire(String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallback
            ,Long time, TimeUnit unit){
        String key=keyPrefix+id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //判断缓存是否命中，未命中返回null
        if (StrUtil.isBlank(json)){
            return null;
        }
        //命中把json反序列为对象
        RedisData redisData=JSONUtil.toBean(json,RedisData.class);
        R r=JSONUtil.toBean((JSONObject) redisData.getData(),type);
        LocalDateTime expireTime=redisData.getExpireTime();
        //过期时间在当前时间后显示未过期
        if (expireTime.isAfter(LocalDateTime.now())){
            return r;//直接返回店铺信息
        }
        //过期则获取互斥锁
        String lockKey= key;
        boolean lock = tryLock(lockKey);
        //判断锁是否获取成功
        if(lock){
            //再查一次缓存 double check
            json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {
                RedisData data = JSONUtil.toBean(json, RedisData.class);
                //如果新的逻辑过期时间已经 > 当前时间，说明别的线程已经重建好了
                if (data.getExpireTime().isAfter(LocalDateTime.now())) {
                    // 释放锁，直接返回新数据，不再重建
                    unLock(lockKey);
                    return JSONUtil.toBean((JSONObject) data.getData(), type);
                }
            }
            //成功则开启独立线程 实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //查数据库
                    R r1 = dbFallback.apply(id);
                    //写入redis
                    this.setWithLogicalExpire(key,r1,time,unit);
                }catch (Exception e){
                    throw new RuntimeException();
                }finally {
                    unLock(lockKey);
                }
            });
        }
        //存进redis
        return r;
    }

    //制作互斥锁
    private boolean tryLock(String lockKey){
        Boolean flag=stringRedisTemplate.opsForValue().setIfAbsent(lockKey,"1",10,TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }
    private void unLock(String lockKey){
        stringRedisTemplate.delete(lockKey);
    }
}
