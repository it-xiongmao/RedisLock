### 1.业务场景引入
在进行代码实现之前，我们先来看一个业务场景：

```
系统A是一个电商系统，目前是一台机器部署，系统中有一个用户下订单的接口，但是用户下订单之前一定要去检查一下库存，确保库存足够了才会给用户下单。
由于系统有一定的并发，所以会预先将商品的库存保存在redis中，用户下单的时候会更新redis的库存。
```

此时系统架构如下：
![在这里插入图片描述](https://img-blog.csdnimg.cn/20200507110227855.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L0JydWNlTGl1X2NvZGU=,size_16,color_FFFFFF,t_70)
但是这样一来会产生一个问题：

```
假如某个时刻，redis里面的某个商品库存为1，此时两个请求同时到来，其中一个请求执行到上图的第3步，更新数据库的库存为0，但是第4步还没有执行。

而另外一个请求执行到了第2步，发现库存还是1，就继续执行第3步。

这样的结果，是导致卖出了2个商品，然而其实库存只有1个。

很明显不对啊！这就是典型的库存超卖问题

此时，我们很容易想到解决方案：用锁把2、3、4步锁住，让他们执行完之后，另一个线程才能进来执行第2步。
```
![在这里插入图片描述](https://img-blog.csdnimg.cn/2020050711030347.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L0JydWNlTGl1X2NvZGU=,size_16,color_FFFFFF,t_70)
按照上面的图，在执行第2步时，使用Java提供的`synchronized`或者`ReentrantLock`来锁住，然后在第4步执行完之后才释放锁。

这样一来，2、3、4 这3个步骤就被“锁”住了，多个线程之间只能串行化执行。

但是好景不长，整个系统的并发飙升，一台机器扛不住了。现在要增加一台机器，如下图：
![在这里插入图片描述](https://img-blog.csdnimg.cn/20200507110335147.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L0JydWNlTGl1X2NvZGU=,size_16,color_FFFFFF,t_70)
增加机器之后，系统变成上图所示，我的天！

假设此时两个用户的请求同时到来，但是落在了不同的机器上，那么这两个请求是可以同时执行了，还是会出现库存超卖的问题。

为什么呢？因为上图中的两个A系统，运行在两个不同的JVM里面，他们加的锁只对属于自己JVM里面的线程有效，对于其他JVM的线程是无效的。

因此，这里的问题是：Java提供的原生锁机制在多机部署场景下失效了

这是因为两台机器加的锁不是同一个锁(两个锁在不同的JVM里面)。

那么，我们只要保证两台机器加的锁是同一个锁，问题不就解决了吗？

此时，就该分布式锁隆重登场了，分布式锁的思路是：

```
在整个系统提供一个全局、唯一的获取锁的“东西”，然后每个系统在需要加锁时，都去问这个“东西”拿到一把锁，这样不同的系统拿到的就可以认为是同一把锁。
至于这个“东西”，可以是Redis、Zookeeper，也可以是数据库。
```
![在这里插入图片描述](https://img-blog.csdnimg.cn/20200507110432627.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L0JydWNlTGl1X2NvZGU=,size_16,color_FFFFFF,t_70)
通过上面的分析，我们知道了库存超卖场景在分布式部署系统的情况下使用Java原生的锁机制无法保证线程安全，所以我们需要用到分布式锁的方案。

那么，如何实现分布式锁呢？

### 2.分布式锁的实现
#### 2.1.分布式锁的简单实现代码
```java
package com.bruceliu.lock;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Transaction;
import redis.clients.jedis.exceptions.JedisException;

import java.util.List;
import java.util.UUID;

/**
 * @BelongsProject: RedisLock
 * @BelongsPackage: com.bruceliu.lock
 * @Author: bruceliu
 * @QQ:1241488705
 * @CreateTime: 2020-05-06 17:51
 * @Description: 分布式锁简单实现
 */
public class DistributedLock {

    private final JedisPool jedisPool;

    public DistributedLock(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    /**
     * 加锁
     * @param lockName       锁的key
     * @param acquireTimeout 获取超时时间
     * @param timeout        锁的超时时间
     * @return 锁标识
     */
    public String lockWithTimeout(String lockName, long acquireTimeout, long timeout) {
        Jedis conn = null;
        String retIdentifier = null;
        try {
            // 获取连接
            conn = jedisPool.getResource();
            // 随机生成一个value
            String identifier = UUID.randomUUID().toString();
            // 锁名，即key值
            String lockKey = "lock:" + lockName;

            // 超时时间，上锁后超过此时间则自动释放锁
            int lockExpire = (int) (timeout / 1000);

            // 获取锁的超时时间，超过这个时间则放弃获取锁
            long end = System.currentTimeMillis() + acquireTimeout;
            while (System.currentTimeMillis() < end) {
                if (conn.setnx(lockKey, identifier) == 1) {
                    conn.expire(lockKey, lockExpire);
                    // 返回value值，用于释放锁时间确认
                    retIdentifier = identifier;
                    return retIdentifier;
                }
                // 返回-1代表key没有设置超时时间，为key设置一个超时时间
                if (conn.ttl(lockKey) == -1) {
                    conn.expire(lockKey, lockExpire);
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (JedisException e) {
            e.printStackTrace();
        } finally {
            if (conn != null) {
                conn.close();
            }
        }
        return retIdentifier;
    }


    /**
     * 释放锁
     * @param lockName   锁的key
     * @param identifier 释放锁的标识
     * @return
     */
    public boolean releaseLock(String lockName, String identifier) {
        Jedis conn = null;
        String lockKey = "lock:" + lockName;
        boolean retFlag = false;
        try {
            conn = jedisPool.getResource();
            while (true) {
                // 监视lock，准备开始事务
                conn.watch(lockKey);
                // 通过前面返回的value值判断是不是该锁，若是该锁，则删除，释放锁
                if (identifier.equals(conn.get(lockKey))) {
                    Transaction transaction = conn.multi();
                    transaction.del(lockKey);
                    List<Object> results = transaction.exec();
                    if (results == null) {
                        continue;
                    }
                    retFlag = true;
                }
                conn.unwatch();
                break;
            }
        } catch (JedisException e) {
            e.printStackTrace();
        } finally {
            if (conn != null) {
                conn.close();
            }
        }
        return retFlag;
    }
}
```
#### 2.2.测试刚才实现的分布式锁
例子中使用50个线程模拟秒杀一个商品，使用–运算符来实现商品减少，从结果有序性就可以看出是否为加锁状态。

模拟秒杀服务，在其中配置了jedis线程池，在初始化的时候传给分布式锁，供其使用。

```java
package com.bruceliu.lock;

import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * @BelongsProject: RedisLock
 * @BelongsPackage: com.bruceliu.lock
 * @Author: bruceliu
 * @QQ:1241488705
 * @CreateTime: 2020-05-07 09:23
 * @Description: TODO
 */
public class SkillService {

    private static JedisPool pool = null;
    private DistributedLock lock = new DistributedLock(pool);

    int n = 500;

    static {
        JedisPoolConfig config = new JedisPoolConfig();
        // 设置最大连接数
        config.setMaxTotal(200);
        // 设置最大空闲数
        config.setMaxIdle(8);
        // 设置最大等待时间
        config.setMaxWaitMillis(1000 * 100);
        // 在borrow一个jedis实例时，是否需要验证，若为true，则所有jedis实例均是可用的
        config.setTestOnBorrow(true);
        pool = new JedisPool(config, "127.0.0.1", 6379, 3000);
    }

    public void seckill() {
        // 返回锁的value值，供释放锁时候进行判断
        String identifier = lock.lockWithTimeout("resource", 5000, 1000);
        System.out.println(Thread.currentThread().getName() + "获得了锁");
        System.out.println(--n);
        lock.releaseLock("resource", identifier);
    }
}

```

#### 2.3.模拟线程进行秒杀服务

```java
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

```
#### 2.4.测试结果
![在这里插入图片描述](https://img-blog.csdnimg.cn/20200507152836273.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L0JydWNlTGl1X2NvZGU=,size_16,color_FFFFFF,t_70)
若注释掉使用锁的部分：

```java
public void seckill() {
    // 返回锁的value值，供释放锁时候进行判断
    //String identifier = lock.lockWithTimeout("resource", 5000, 1000);
    System.out.println(Thread.currentThread().getName() + "获得了锁");
    System.out.println(--n);
    //lock.releaseLock("resource", identifier);
}
```
从结果可以看出，有一些是异步进行的：
![在这里插入图片描述](https://img-blog.csdnimg.cn/20200507153044362.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L0JydWNlTGl1X2NvZGU=,size_16,color_FFFFFF,t_70)




