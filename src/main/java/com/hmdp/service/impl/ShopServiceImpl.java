package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;
    @Resource
    private IShopTypeService shopTypeService;

    /**
     * 附近搜索的半径（米）
     */
    private static final int NEARBY_RADIUS_METERS = 5000;

    /**
     * sortBy 允许的排序字段白名单。
     * 这个值要拼进 SQL 的 order by，不做白名单就是 SQL 注入漏洞
     */
    private static final Set<String> ALLOWED_SORT_FIELDS =
            new HashSet<>(Arrays.asList("comments", "score"));

    /**
     * GEO 一次最多取多少个候选。
     * 按人气/评分排序时需要在候选集里排序再分页，所以不能像按距离那样只取前 end 条。
     * 超过这个数就只排前 MAX_GEO_CANDIDATES 个候选，会记日志提示被截断。
     */
    private static final int MAX_GEO_CANDIDATES = 200;
    @Override
    public Result queryById(Long id) {
        //解决缓存穿透
        Shop shop=cacheClient.queryWithPassThrough(
                RedisConstants.CACHE_SHOP_KEY
                ,id
                ,Shop.class,this::getById,RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);
        return Result.ok(shop);


    }

    //解决缓存击穿的方法
    public Shop queryWithMutex(Long id){
        //判断店铺是否存在 redis 存在直接返回
        String shopJson1 = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY+id);
        if (StrUtil.isNotBlank(shopJson1)){
            //转换成 shop
            Shop shop= JSONUtil.toBean(shopJson1,Shop.class);
            return shop;
        }
        //判断是否为空
        if(shopJson1!=null){
            return null;
        }
        //缓存重建
        //获取互斥锁
        String key= RedisConstants.LOCK_SHOP_KEY+id;
        Shop shop=null;
        try {
            boolean isLock = tryLock(key);
            if(!isLock){
                //无法获取就线程休眠
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //获取锁之后再次判断redis是否存在数据，因为有可能在获取到锁的过程中其他线程创建了redis缓存;
            String shopJson2 = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY+id);
            if (StrUtil.isNotBlank(shopJson2)){
                //转换成 shop
                shop= JSONUtil.toBean(shopJson2,Shop.class);
                return shop;
            }
            //不存在从数据库里取
            shop=getById(id);
            if (shop==null){
                //存入空值，防止穿透
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY,"",3,TimeUnit.MINUTES);
                return null;
            }
            //存进redis
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id
                    ,JSONUtil.toJsonStr(shop)
                    , RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        }catch (InterruptedException e){
            throw new RuntimeException(e);
        }finally {
            unLock(key);
        }
        return shop;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);
    //逻辑过期解决缓存击穿
    public Shop queryWithLogicalExprire(Long id){
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY+id);
        //判断缓存是否命中，未命中返回null
        if (StrUtil.isBlank(shopJson)){
            return null;
        }
        //命中把json反序列为对象
        RedisData redisData=JSONUtil.toBean(shopJson,RedisData.class);
        Shop shop=JSONUtil.toBean((JSONObject) redisData.getData(),Shop.class);
        LocalDateTime expireTime=redisData.getExpireTime();
        //过期时间在当前时间后显示未过期
        if (expireTime.isAfter(LocalDateTime.now())){
            return shop;//直接返回店铺信息
        }
        //过期则获取互斥锁
        String lockKey= RedisConstants.LOCK_SHOP_KEY+id;
        boolean lock = tryLock(lockKey);
        //判断锁是否获取成功
        if(lock){
            //成功则开启独立线程 实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //重建缓存
                    this.saveShopRedis(id,30L);
                }catch (Exception e){
                    throw new RuntimeException();
                }finally {
                    unLock(lockKey);
                }
            });
        }
        //存进redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id
                ,JSONUtil.toJsonStr(shop)
                , RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    //制作互斥锁
    private boolean tryLock(String lockKey){
        Boolean flag=stringRedisTemplate.opsForValue().setIfAbsent(lockKey,"1",10,TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }
    private void unLock(String lockKey){
        stringRedisTemplate.delete(lockKey);
    }
    public void saveShopRedis(Long id,Long expireSeconds){
        //查询店铺信息
        Shop shop=getById(id);
        //封装逻辑过期时间
        RedisData redisData=new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue()
                .set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }
//    //解决缓存穿透的方法
//    public Shop queryWithPassThrough(Long id){
//        //判断店铺是否存在 redis 存在直接返回
//        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY+id);
//        if (StrUtil.isNotBlank(shopJson)){
//            //转换成 shop
//            Shop shop= JSONUtil.toBean(shopJson,Shop.class);
//            return shop;
//        }
//        //判断是否为空
//        if(shopJson!=null){
//            return null;
//        }
//        //不存在从数据库里取
//        Shop shop=getById(id);
//        if (shop==null){
//            //存入空值，防止穿透
//            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY,"",3,TimeUnit.MINUTES);
//            return null;
//        }
//        //存进redis
//        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id
//                ,JSONUtil.toJsonStr(shop)
//                , RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        return shop;
//    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id=shop.getId();
        if (id==null){
            return Result.fail("店铺id不能为空");
        }
        //优先更新数据库，再删除redis缓存
        updateById(shop);
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY+id);
        return null;
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y, String sortBy) {
        //要不要按 sortBy 排序，先过一遍白名单
        boolean sortByColumn = StrUtil.isNotBlank(sortBy) && ALLOWED_SORT_FIELDS.contains(sortBy);

        //判断是否需要坐标查询
        if (x == null || y == null) {
            //不需要坐标，直接查数据库
            QueryWrapper<Shop> wrapper = new QueryWrapper<Shop>().eq("type_id", typeId);
            if (sortByColumn) {
                wrapper.orderByDesc(sortBy);
            }
            Page<Shop> page = page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE), wrapper);
            return Result.ok(page.getRecords());
        }

        //需要坐标：先用 GEO 圈出半径内的候选店铺。
        //按距离排序最多只要前 end 条；但要按人气/评分重排，就得把候选多取一些再交给数据库排
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        int limit = sortByColumn ? MAX_GEO_CANDIDATES : end;
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> candidates =
                geoSearch(RedisConstants.SHOP_GEO_KEY + typeId, x, y, limit);

        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        //半径内无店铺，或已经翻到最后一页之后（否则下面会拼出非法的 id IN ()）
        if (candidates.size() <= from) {
            return Result.ok(Collections.emptyList());
        }

        //距离信息单独存一份，最后回填给前端显示
        Map<String, Distance> distanceMap = new HashMap<>(candidates.size());
        for (GeoResult<RedisGeoCommands.GeoLocation<String>> r : candidates) {
            distanceMap.put(r.getContent().getName(), r.getDistance());
        }

        List<Shop> shops;
        if (sortByColumn) {
            //按人气/评分：GEO 只负责圈定范围，排序和分页都交给数据库做才准
            if (candidates.size() >= MAX_GEO_CANDIDATES) {
                log.warn("半径内候选已达上限 {}，按 {} 排序的结果可能不完整", MAX_GEO_CANDIDATES, sortBy);
            }
            List<Long> ids = candidates.stream()
                    .map(r -> Long.valueOf(r.getContent().getName()))
                    .collect(Collectors.toList());
            shops = query().in("id", ids)
                    .orderByDesc(sortBy)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE))
                    .getRecords();
        } else {
            //默认按距离：GEO 返回的本来就是距离升序，直接切出当前页
            List<Long> ids = candidates.stream()
                    .skip(from)
                    .limit(SystemConstants.DEFAULT_PAGE_SIZE)
                    .map(r -> Long.valueOf(r.getContent().getName()))
                    .collect(Collectors.toList());
            if (ids.isEmpty()) {
                return Result.ok(Collections.emptyList());
            }
            shops = query().in("id", ids)
                    .last("order by field(id," + StrUtil.join(",", ids) + ")")
                    .list();
        }

        for (Shop shop : shops) {
            Distance distance = distanceMap.get(shop.getId().toString());
            if (distance != null) {
                shop.setDistance(distance.getValue());
            }
        }
        return Result.ok(shops);
    }

    @Override
    public Result queryShopByNearby(Double x, Double y, Integer current) {
        if (x == null || y == null) {
            return Result.fail("缺少定位坐标");
        }
        //GEO 数据是按店铺类型分 key 存的（shop:geo:{typeId}），
        //要跨类型查"附近"，就得把每个类型的 key 都查一遍再合并。
        //类型清单从数据库取，不用 Redis 的 KEYS 去扫（KEYS 是 O(N)，生产环境应当禁用）
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> all = new ArrayList<>();
        for (ShopType type : shopTypeService.list()) {
            //每个类型各自只取前 end 条就够了：
            //一个店铺若能进全量前 end 名，它在自己类型里也必然在前 end 名之内，不会漏
            all.addAll(geoSearch(RedisConstants.SHOP_GEO_KEY + type.getId(), x, y, end));
        }
        //各类型内部本来就按距离有序，但合并后要整体重排一次
        all.sort(Comparator.comparingDouble(r -> r.getDistance().getValue()));

        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        if (all.size() <= from) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = new ArrayList<>();
        Map<String, Double> distanceMap = new HashMap<>(all.size());
        all.stream().skip(from).limit(SystemConstants.DEFAULT_PAGE_SIZE).forEach(r -> {
            String shopId = r.getContent().getName();
            ids.add(Long.valueOf(shopId));
            distanceMap.put(shopId, r.getDistance().getValue());
        });
        if (ids.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Shop> shops = query().in("id", ids)
                .last("order by field(id," + StrUtil.join(",", ids) + ")")
                .list();
        for (Shop shop : shops) {
            Double distance = distanceMap.get(shop.getId().toString());
            if (distance != null) {
                shop.setDistance(distance);
            }
        }
        return Result.ok(shops);
    }

    /**
     * 在某个 GEO key 里按坐标查附近成员，返回距离升序的结果
     */
    private List<GeoResult<RedisGeoCommands.GeoLocation<String>>> geoSearch(
            String key, Double x, Double y, int limit) {
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(NEARBY_RADIUS_METERS),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                                .includeDistance().limit(limit));
        return results == null ? Collections.emptyList() : results.getContent();
    }
}
