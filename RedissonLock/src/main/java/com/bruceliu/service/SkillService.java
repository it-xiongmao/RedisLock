package com.bruceliu.service;

import com.bruceliu.utils.LockUtil;

import java.util.concurrent.TimeUnit;

/**
 * @BelongsProject: RedissonLock
 * @BelongsPackage: com.bruceliu.service
 * @Author: bruceliu
 * @QQ:1241488705
 * @CreateTime: 2020-05-08 10:26
 * @Description: TODO
 */
public class SkillService {

    int n = 500;

    public void seckill() {
        //加锁
        //LockUtil.lock("resource", TimeUnit.SECONDS,5000);
        try {
            System.out.println(Thread.currentThread().getName() + "获得了锁");
            Thread.sleep(3000);
            System.out.println(--n);
        } catch (Exception e) {
            //异常处理
        }finally{
            //释放锁
            //LockUtil.unlock("resource");
            System.out.println(Thread.currentThread().getName() + "释放了锁");
        }
    }
}
