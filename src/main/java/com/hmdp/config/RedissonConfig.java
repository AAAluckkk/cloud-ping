package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(){
        //配置类
        Config config=new Config();
        //添加地址 config.useClusterServers()添加集群地址
        config.useSingleServer().setAddress("redis://localhost:6379");//设置密码.setPassword();
        return Redisson.create(config);
    }
}
