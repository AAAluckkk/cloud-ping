package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Redis 缓存的封装类。
 *
 * 读写走同一套逻辑：逻辑过期 + 空值缓存。一次查询同时处理两个问题：
 *   - 缓存击穿：热点 key 逻辑过期时，只有一个线程去重建，其余线程立刻返回旧值，不会一起打到数据库
 *   - 缓存穿透：数据库里查不到的数据，缓存一个空值（短 TTL），避免同一个不存在的 id 反复查库
 */
@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 重建缓存的线程池。
     * 抢到锁的线程把重建任务丢进来就返回，请求线程不阻塞在数据库查询上
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 写入带逻辑过期时间的缓存。
     *
     * 注意这里给 Redis 的 key 不设 TTL：过期与否由 RedisData 里的 expireTime 判断。
     * 这样"过期"之后数据还在 Redis 里，仍然能把旧值返回给请求 —— 这是防击穿的前提，
     * 如果直接用 Redis 的 TTL，key 一过期数据就没了，所有请求只能一起去查库。
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(JSONUtil.toJsonStr(value));
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 查询缓存。
     *   - 命中且未逻辑过期：直接返回
     *   - 命中但已逻辑过期：返回旧值，同时由抢到锁的线程异步重建
     *   - 未命中：回源查库并写缓存；查不到就写空值防穿透
     */
    public <R,ID> R queryWithLogicalExpire(String keyPrefix, String lockPrefix, ID id, Class<R> type,
                                           Function<ID,R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 命中的是空值标记（空字符串），说明库里确实没有这条数据，直接返回 null
        if (json != null && json.isEmpty()) {
            return null;
        }

        if (StrUtil.isNotBlank(json)) {
            RedisData redisData = parseRedisData(json);
            if (redisData != null) {
                R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
                // 未逻辑过期，直接返回
                if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
                    return r;
                }
                // 已逻辑过期：只有抢到锁的线程去重建，其余线程立刻返回旧值
                String lockKey = lockPrefix + id;
                if (tryLock(lockKey)) {
                    CACHE_REBUILD_EXECUTOR.submit(() -> {
                        try {
                            R fresh = dbFallback.apply(id);
                            if (fresh == null) {
                                // 数据已经被删了，把缓存清掉
                                stringRedisTemplate.delete(key);
                            } else {
                                setWithLogicalExpire(key, fresh, time, unit);
                            }
                        } catch (Exception e) {
                            log.error("缓存重建失败，key={}", key, e);
                        } finally {
                            unLock(lockKey);
                        }
                    });
                }
                return r;
            }
        }

        // 未命中（或存的是不认识的格式）：回源查库
        return loadAndCache(key, id, type, dbFallback, time, unit);
    }

    /**
     * 回源查库并写缓存。
     * 查不到时写入空值（短 TTL），防止同一个不存在的 id 反复打到数据库
     */
    private <R,ID> R loadAndCache(String key, ID id, Class<R> type,
                                  Function<ID,R> dbFallback, Long time, TimeUnit unit){
        R r = dbFallback.apply(id);
        if (r == null) {
            stringRedisTemplate.opsForValue()
                    .set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        setWithLogicalExpire(key, r, time, unit);
        return r;
    }

    /**
     * 把缓存里的 json 解析成 RedisData。
     * 解析不出来（旧格式、空值标记等）返回 null，交给调用方当成未命中处理，
     * 免得拿一个 data 为 null 的对象去反序列化把请求打挂
     */
    private RedisData parseRedisData(String json){
        try {
            RedisData redisData = JSONUtil.toBean(json, RedisData.class);
            if (redisData.getData() == null || redisData.getExpireTime() == null) {
                return null;
            }
            return redisData;
        } catch (Exception e) {
            return null;
        }
    }

    // 抢重建锁：抢不到就说明已经有线程在重建了
    private boolean tryLock(String lockKey){
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String lockKey){
        stringRedisTemplate.delete(lockKey);
    }
}
