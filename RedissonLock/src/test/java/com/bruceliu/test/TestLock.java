package com.bruceliu.test;

import com.bruceliu.App;
import com.bruceliu.service.SkillService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

/**
 * @BelongsProject: RedissonLock
 * @BelongsPackage: com.bruceliu.test
 * @Author: bruceliu
 * @QQ:1241488705
 * @CreateTime: 2020-05-08 10:29
 * @Description: TODO
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = App.class)
public class TestLock {

    @Test
    public void testLock() throws Exception{
        SkillService service = new SkillService();
        for (int i = 0; i < 10; i++) {
            ThreadA threadA = new ThreadA(service);
            threadA.setName("ThreadNameA->"+i);
            threadA.start();
        }
        Thread.sleep(50000);
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

