package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService
{
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct //这个注解代表在当前类初始化完毕之后来执行
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while(true){
                try {
                    //1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder)
    {
        //1.获取用户
        Long userId = voucherOrder.getUserId();
        // 2.创建锁对象
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        // 3.尝试获取锁
        boolean isLock = redisLock.tryLock();
        // 4.判断是否获得锁成功
        if (!isLock) {
            // 获取锁失败，直接返回失败或者重试
            log.error("不允许重复下单！");
            return;
        }
        try {
            //注意：由于是spring的事务是放在threadLocal中，此时的是多线程，事务会失效
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            redisLock.unlock();
        }
    }

    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        //2.判断结果是否是0
        int r = result.intValue();
        if(r != 0){
            //2.1.不为0，没有购买资格
            return Result.fail(r == 1 ? "库存不足！" : "不能重复下单");
        }

        //2.2.为0，有购买资格，把下单信息保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        //2.3.设置订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //2.4设置用户id(通过拦截器获得)
        voucherOrder.setUserId(UserHolder.getUser().getId());
        //2.5设置代金卷id
        voucherOrder.setVoucherId(voucherId);
        //2.6.放入阻塞队列
        orderTasks.add(voucherOrder);

        //3.获得代理对象
        proxy = (IVoucherOrderService)AopContext.currentProxy();

        //4.返回订单id
        return Result.ok(orderId);
    }

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //1.查询优惠卷
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//
//        //2.判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始！");
//        }
//
//        //3.判断秒杀是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束！");
//        }
//
//        //4.判断库存是否充足
//        Integer stock = voucher.getStock();
//        if (stock < 1) {
//            return Result.fail("库存不足！");
//        }
//
//        Long userId = UserHolder.getUser().getId();
//        //toString方法的底层是创建一个新的字符串，因此可能用户id一样，但是锁也不一样
//        // 加了intern之后就可以保证用户id的值一样时，锁就一样
//        //synchronized (userId.toString().intern())
//
//        //获取锁对象
//        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//
//        //获取锁
//        boolean isLock = lock.tryLock();
//
//        if(!isLock){
//            //获取锁失败
//            return Result.fail("不允许重复下单！");
//        }
//
//        try {
//            //这里事务的动态代理没太听懂（P54集）
//            //获取代理对象（事务）
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            //释放锁
//            lock.unlock();
//        }
//    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder)
    {
        //5.一人一单
        Long userId = UserHolder.getUser().getId();

        //查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        //判断是否存在
        if(count > 0){
            // 用户已经购买过了
            log.error("用户已经购买过了");
            return ;
        }

        //6.扣减库存
        //这是使用的还是mp进行sql语句的编写
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock",0) //使用乐观锁的CAS方法解决优惠卷超卖问题(gt表示大于)
                .update();
        if(!success){
            // 扣减失败
            log.error("库存不足");
            return ;
        }

        //7.创建订单
        save(voucherOrder);
    }
}
