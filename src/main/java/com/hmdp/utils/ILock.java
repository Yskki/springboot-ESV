package com.hmdp.utils;

public interface ILock
{
    /**
     *
     * @param timeoutSec 锁持有的时间，过期后自动释放
     * @return
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
