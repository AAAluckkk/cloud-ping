package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 */
@Slf4j
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public List<ShopType> queryAll() {
        //查询 redis 里所有店铺类型
        String shopList=stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_TYPE);
        if (StrUtil.isNotBlank(shopList)){
            return JSONUtil.toList(shopList, ShopType.class);
        }
        // 3. 未命中，查数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        // 4. 写进 Redis（转成 JSON 字符串存）
        stringRedisTemplate.opsForValue().set(
                RedisConstants.CACHE_SHOP_TYPE, JSONUtil.toJsonStr(typeList));
        return typeList;
    }
}
