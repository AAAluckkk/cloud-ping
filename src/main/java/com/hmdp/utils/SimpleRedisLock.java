package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;
public class SimpleRedisLock implements ILock{

    private StringRedisTemplate stringRedisTemplate;
    private String name;
    private static final String KEY_PREFIX="lock:";
    private static final String ID_PREFIX= UUID.randomUUID().toString(true)+"-";
    //声明一个lua脚本对象
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static{
        //创建对象
        UNLOCK_SCRIPT=new DefaultRedisScript<>();
        //指向lua脚本路径
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        //设置返回类型
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate,String name){
        this.name=name;
        this.stringRedisTemplate=stringRedisTemplate;
    }
    @Override
    public boolean tryLock(Long timeoutSec) {
        //获取线程 id 作为锁的标识
        Long id = Thread.currentThread().getId();
        //设置锁，设置时间
        Boolean b = stringRedisTemplate.opsForValue().
                setIfAbsent(KEY_PREFIX + name, ID_PREFIX + id, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(b);
    }
    @Override
    public void unLock() {
        // 调用lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());
    }

//    @Override
//    public void unLock() {
//        String id=stringRedisTemplate.opsForValue().get(KEY_PREFIX+name);
//        String threadId = ID_PREFIX+Thread.currentThread().getId();
//        //判断目前锁是否被占用
//        if (threadId.equals(id)){
//            //释放锁
//            stringRedisTemplate.delete(KEY_PREFIX+name);
//        }
//    }
}
