package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService
{
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;
    @Override
    public Result queryById(Long id)
    {
        //缓存穿透
//        Shop shop = cacheClient
//                .queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class,this::getById, CACHE_SHOP_TTL,TimeUnit.MINUTES);

        //互斥锁解决缓存穿透
        //Shop shop = queryWithMutex(id);

        //逻辑过期解决缓存穿透
        Shop shop = cacheClient
                .queryWithLogicalExpire(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        if(shop == null){
            return Result.fail("店铺不存在！");
        }

        //返回
        return Result.ok(shop);
    }

    //创建线程池
//    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
//    public Shop queryWithLogicalExpire(Long id)
//    {
//        //1.从redis查询缓存
//        String key = CACHE_SHOP_KEY + id;
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//
//        //2.判断是否存在
//        if(StrUtil.isBlank(shopJson)) {
//            //3.不存在，直接返回
//            return null;
//        }
//        //4.命中，需要先把json反序列化为对象
//        RedisData redisData = JSONUtil.toBean(shopJson,RedisData.class);
//        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(),Shop.class);
//        LocalDateTime expireTime = redisData.getExpireTime();
//
//        //5.判断是否过期
//        if(expireTime.isAfter(LocalDateTime.now())){
//            //5.1.未过期，直接返回店铺信息
//            return shop;
//        }
//
//        //5.2.过期，需要缓存重建
//        //6.缓存重建
//        //6.1获取互斥锁
//        String lockKey = LOCK_SHOP_KEY + id;
//        boolean isLock = tryLock(lockKey);
//
//        //6.2判断是否获取锁成功
//        if(isLock){
//            //6.3成功，开启独立线程进行缓存重建
//            CACHE_REBUILD_EXECUTOR.submit( ()->{
//
//                try{
//                    //重建缓存
//                    this.saveShop2Redis(id,20L);
//                }catch (Exception e){
//                    throw new RuntimeException(e);
//                }finally {
//                    unlock(lockKey);
//                }
//            });
//        }
//
//        //6.4返回过期的商铺信息
//        return shop;
//    }

//    public Shop queryWithMutex(Long id)
//    {
//        //1.从redis查询缓存
//        String key = CACHE_SHOP_KEY + id;
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//
//        //2.存在，直接返回
//        if(StrUtil.isNotBlank(shopJson)) {
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return shop;
//        }
//        //3.判断是否是空值
//        if(shopJson != null){
//            return null;
//        }
//        String lockKey = null;
//        Shop shop = null;
//
//
//        try {
//            //4.缓存重建
//            //4.1获取互斥锁
//            lockKey = "lock:shop:" + id;
//            boolean isLock = tryLock(lockKey);
//
//            //4.2判断是否获取成功
//            if(!isLock){
//                //4.3如果失败，休眠并重试
//                Thread.sleep(50);//让程序休眠50ms
//                queryWithMutex(id);//并返回重新查询
//            }
//
//            //4.4如果成功，根据id查询数据库
//            shop = getById(id);
//
//            //5.数据库中不存在，返回错误信息
//            if(shop == null){
//                //不存在，缓存空值
//                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
//                return null;
//            }
//            //6.数据库中存在，写入redis
//            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        } finally {
//            //7.释放互斥锁
//            unlock(lockKey);
//        }
//        return shop;
//    }
/*
    public Shop queryWithPassThrough(Long id)
    {
        //1.从redis查询缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //2.存在，直接返回
        if(StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断是否是空值
        if(shopJson != null){
            return null;
        }

        //3.不存在，去数据库中查询
        Shop shop = getById(id);

        //4.数据库中不存在，返回错误信息
        if(shop == null){
            //不存在，缓存空值
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        //5.数据库中存在，写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shop;
    }

*/

    //这里用redis中的setnx来模拟锁
//    private boolean tryLock(String key)
//    {
//        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
//        return BooleanUtil.isTrue(flag);
//    }
//
//    private void unlock(String key)
//    {
//        stringRedisTemplate.delete(key);
//    }
//
//    public void saveShop2Redis(Long id,Long expireSeconds)
//    {
//        //1.查询店铺数据
//        Shop shop = getById(id);
//
//        //2.封装逻辑过期时间
//        RedisData redisData = new RedisData();
//        redisData.setData(shop);
//        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
//
//        //3.写入Redis
//        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
//
//    }

    @Override
    @Transactional
    public Result update(Shop shop)
    {
        if(shop.getId() == null){
            return Result.fail("店铺id不能为空！");
        }
        //1.修改数据库信息
        updateById(shop);

        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());

        return Result.ok();
    }

    //这个由于本地redis版本过低，导致报错，需要将本地redis版本升到6.2以上
    //现在windows版本最高也达不到6.2，需要下载Linus版本的
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y)
    {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }
}
