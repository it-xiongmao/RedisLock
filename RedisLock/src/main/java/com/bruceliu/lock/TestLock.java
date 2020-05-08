package com.bruceliu.lock;

/**
 * @BelongsProject: RedisLock
 * @BelongsPackage: com.bruceliu.lock
 * @Author: bruceliu
 * @QQ:1241488705
 * @CreateTime: 2020-05-07 09:24
 * @Description: TODO
 */
public class TestLock {

    public static void main(String[] args) {
        SkillService service = new SkillService();
        for (int i = 0; i < 50; i++) {
            ThreadA threadA = new ThreadA(service);
            threadA.setName("ThreadNameA->"+i);
            threadA.start();
        }
    }
}

class ThreadA extends Thread {

    private SkillService skillService;

    public ThreadA(SkillService skillService) {
        this.skillService = skillService;
    }

    @Override
    public void run() {
        skillService.seckill();
    }
}
