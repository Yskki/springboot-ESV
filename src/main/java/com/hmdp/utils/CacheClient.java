package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j
public class CacheClient
{
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        //写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //我们是把一些功能包装成工具类了，所以要适用所有的类型，参数
    //不同功能类型，参数可能不一样，所以要使用泛型
    public <R,ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID,R>dbFallback,Long time,TimeUnit unit)
    {
        //1.从redis查询缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        //2.存在，直接返回
        if(StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        //判断是否是空值
        if(json != null){
            return null;
        }

        //3.不存在，去数据库中查询
        //这里我们需要调用者传递
        R r = dbFallback.apply(id);

        //4.数据库中不存在，返回错误信息
        if(r == null){
            //不存在，缓存空值
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        //5.数据库中存在，写入redis
        this.set(key,r,time,unit);

        return r;
    }


    //创建线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public <R,ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID,R>dbFallback,Long time,TimeUnit unit)
    {
        //1.从redis查询缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        //2.判断是否存在
        if(StrUtil.isBlank(json)) {
            //3.不存在，直接返回
            return null;
        }
        //4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json,RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(),type);
        LocalDateTime expireTime = redisData.getExpireTime();

        //5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //5.1.未过期，直接返回店铺信息
            return r;
        }

        //5.2.过期，需要缓存重建
        //6.缓存重建
        //6.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);

        //6.2判断是否获取锁成功
        if(isLock){
            //6.3成功，开启独立线程进行缓存重建
            CACHE_REBUILD_EXECUTOR.submit( ()->{
                try{
                    //查询数据库
                    R r1 = dbFallback.apply(id);
                    //写入redis
                    this.setWithLogicalExpire(key,r1,time,unit);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    unlock(lockKey);
                }
            });
        }
        //6.4返回过期的商铺信息
        return r;
    }

    //这里用redis中的setnx来模拟锁
    private boolean tryLock(String key)
    {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key)
    {
        stringRedisTemplate.delete(key);
    }
}
