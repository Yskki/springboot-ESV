package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.yaml.snakeyaml.events.Event;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock
{
    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeoutSec)
    {
        //获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();

        //获取锁
        String key = KEY_PREFIX + name;
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, threadId + "", timeoutSec, TimeUnit.SECONDS);

        //这里因为返回类型不一致，所以直接返回success会自动拆箱装箱，可能会有空指针风险
        //也可以将success转化为boolean返回
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        //调用lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());
    }

//    @Override
//    public void unlock() {
//        //获取线程标识
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//
//        //获取锁中的标识
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//
//        //判断是否一致
//        if(threadId.equals(id)){
//            //释放锁
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//    }
}
