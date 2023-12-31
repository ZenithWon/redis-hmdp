# Redis学习笔记

### 短信登录实现

1. 短信验证码存放在redis中，并且使用用户的手机号作为key实现验证码的唯一性。
2. 登录成功后生成用户的唯一标识token，然后自定义拦截器拦截所有需要登录才能访问的接口，判断是否携带token以及携带token的合法性
3. 为了能保证用户在登陆状态token的可用性，在token验证拦截器前面再添加一个token刷新拦截器，使得用户只要在操作系统就可以一直刷新token，而不是访问特定接口才能刷新token

<img src="https://raw.githubusercontent.com/ZenithWon/figure/master/image-20231110102441255.png" alt="image-20231110102441255" style="zoom: 33%;" />



### Redis缓存实现

##### 原因

* 对于某些热点数据，需要频繁访问数据库的，会对数据库造成很大的读写压力，并且由于数据库基于磁盘读写会导致执行时间很长，造成不好的用户体验。

##### 解决方案

* 使用redis作为缓存中间件，依靠其基于内存读写的特性，可以解决上述问题，具体流程如下
  1. 首先用户发起查询请求后，先在redis中查询
  2. 如果redis存在该数据那么直接返回，否则再去数据库中查询
  3. 在数据库中查询完成后，将数据插入redis中再返回数据

<img src="https://raw.githubusercontent.com/ZenithWon/figure/master/image-20231110103220876.png" alt="image-20231110103220876" style="zoom: 33%;" />

##### 问题一：如何保证数据库和缓存的数据一致性

* 在后台发生对数据的修改、删除等更新操作时，如果缓存中也包含该数据，那么就会导致数据的不一致性
* 有两种解决方案
  1. 同步更新缓存数据
  2. 直接删除缓存数据
* 一般来说，会**使用第二种操作**，因为更新缓存数据如果后面不发生查询操作就会出现浪费资源

##### 问题二：缓存穿透问题

* 如果某个用户频繁的访问一个数据库中不存在的数据，那么每次请求都会打到数据库上，这仍然给数据库造成了很大的压力
* 有两种解决方案
  1. 在第一次查到数据为空时，也向redis中插入一个空值
  2. 使用布隆过滤器，在请求之前加入一次判断（布隆过滤器可能会误判，但是存在的一定存在）

<img src="https://raw.githubusercontent.com/ZenithWon/figure/master/image-20231110110314383.png" alt="image-20231110110314383" style="zoom: 33%;" />

##### 问题三：缓存雪崩问题

* 如果一大批热点数据的key在redis中同时过期了，或者redis直接宕机了，那么一瞬间所有的查询都会请求到数据库上，造成数据库崩溃
* 这里的解决方法大部分都是部署问题了
  1. 给不同的Key的TTL添加随机值
  2. 利用Redis集群提高服务的可用性
  3. 给缓存业务添加降级限流策略
  4. 给业务添加多级缓存

##### 问题四：缓存击穿问题

* 考虑一种并发的场景，如果多个线程同时访问一个资源，恰巧这个资源不在缓存且构造数据的时间有很长，那么同样也会有多个线程访问数据库并且都在构造同一个数据，这机既会导致数据库崩溃也会浪费大量资源

* 有两种解决方案

  1. 互斥锁：当发现没有数据时会先去争抢锁，如果抢到了就更访问数据库构造数据，否则重新争抢锁直到缓存中有数据或者抢到锁。

  <img src="https://raw.githubusercontent.com/ZenithWon/figure/master/image-20231110111517285.png" alt="image-20231110111517285" style="zoom: 33%;" />

  2. 逻辑过期（搭配缓存预热）：给数据设置逻辑过期时间，但在redis保存的是永久的，当查到数据后(一定会查到)，判断是否过期如果过期则争抢锁。争抢到了则开启一个独立线程去构造数据，然后自己返回旧值；没争抢到直接返回旧值。

<img src="https://raw.githubusercontent.com/ZenithWon/figure/master/image-20231110112209272.png" alt="image-20231110112209272" style="zoom: 33%;" />



### Redis实现高并发场景下的秒杀

##### 全局唯一ID

* 为了实现集群部署情况下的订单ID，并且保证ID不直接包含某种特殊信息（比如直接使用数据库的自增会让用户猜出来每日的订单数量），需要自定义一种ID的生成策略。
* 本项目使用的策略如下

<img src="https://raw.githubusercontent.com/ZenithWon/figure/master/image-20231110131033183.png" alt="image-20231110131033183" style="zoom: 33%;" />



##### 实现秒杀功能

* 这里不考虑高并发情况，只是西安基本功能

* 流程如下

<img src="https://raw.githubusercontent.com/ZenithWon/figure/master/image-20231110131133647.png" alt="image-20231110131133647" style="zoom:33%;" />

##### 问题一：库存超卖

* 当处于高并发的场景下，可能会有多个线程同时读取数据库导致只剩最后一单时多个线程认为库存还足够，于是都进行购买操作，这会导致库存超卖的问题。
* 解决方案有两种（乐观锁/悲观锁）

<img src="https://raw.githubusercontent.com/ZenithWon/figure/master/image-20231110131345760.png" alt="image-20231110131345760" style="zoom: 33%;" />



##### 问题二：实现一人一单

* 基本思想：在下单前先查询订单表，如果存在就不做操作
* 但是如果同一时间多个线程都查询发现没有买过，那么就会导致一人多单的情况
* 解决方法：对创建订单的代码加锁synchronized，并且是根据用户ID加锁，而不是全局加锁

##### 问题三：集群情况下的一人一单

* 由于synchronized锁仅对单机有效，当集群部署时同样会导致并发问题
* 解决方案：使用**分布式锁**



### 分布式锁

##### 基本实现方式

<img src="https://raw.githubusercontent.com/ZenithWon/figure/master/image-20231110132439079.png" alt="image-20231110132439079" style="zoom:33%;" />

* 注意：这里没有获得锁后采取的措施可以根据业务场景决定，是重试还是返回报错信息

```java
public class SimpleRedisLock implements ILock{
    private StringRedisTemplate stringRedisTemplate;
    private String name;
    private static final String KEY_PREFIX="lock:";


    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate , String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        long threadId = Thread.currentThread().getId();

        Boolean lock = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name , "thread"+threadId , timeoutSec , TimeUnit.SECONDS);
        return BooleanUtil.isTrue(lock);
    }

    @Override
    public void unLock() {
        stringRedisTemplate.delete(KEY_PREFIX + name);
    }
}
```

##### 问题一：误删问题

* 如果在线程1阻塞的时候lock超时没了，这时候线程2获取到了锁，当线程1恢复工作时候不加判断会直接将线程2的锁删除掉的。
* 解决方法：添加锁的时候附带线程自己的唯一标识。
* 但是由于释放锁时先查询再删除，如果查询后阻塞仍然会出现误删的情况
* 解决方法：使用Lua实现查询加删除的**原子操作**

**以上问题其实均可通过Redisson解决**



### 秒杀优化

##### 基本思路：异步秒杀

* 秒杀优惠券的库存信息放在redis中，并且构造一个set保存该优惠券下单的用户

* 在用户下单的时候只需要判断是否满足下单需求（如是否买过，库存是否充足），满足就直接更新redis库存，生成一个订单ID返回并放入一个队列中。
* 再开启一个独立线程，读取队列，创建订单修改数据库。

##### Redis消息队列

* 由于需要将订单信息放入一个队列中，后面有独立线程来取，因此这里需要构造一个消息队列
* 可以用RabbitMQ解决，但是这里我们用redis实现
* 有三种实现方式
  1. 基于List
  2. 基于PubSub
  3. 基于Stream
* 这里使用Stream

