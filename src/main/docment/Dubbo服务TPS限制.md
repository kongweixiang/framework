# Dubbo源码解析之TPS控制
dubbo使用Filter，再调用服务前对对付进行tps的验证判断,在进行Dubbo服务调用TPS限制，主要委托TPSLimiter实现类进行TPS的限制。
TPSLimiter，返回布尔值，以允许在最后一次调用和当前调用内允许对提供程序服务的方法或特定方法的调用。
```java
public interface TPSLimiter {

    /**
     * 根据规则判定当前invocation的url能否被调用
     *
     * @param url        url
     * @param invocation invocation
     * @return true allow the current invocation, otherwise, return false
     */
    boolean isAllowable(URL url, Invocation invocation);

}
```
例如：如果方法m1在1秒内允许5次调用，则如果一分钟内进行了6次调用，则第6次调用不允许，`isAllowable`返回`false`。  
我们看一下TPSLimiter的具体实现，首先我们看一下Dubbo是如何存储和判断调用状态的，`StatItem` 类，通过控制TPS总访问量递减的方式实现是否应在配置的时间间隔内允许对服务提供者方法的特定调用，作为调用状态，它包含key的名称（例如方法），上次调用时间，间隔和速率计数。
```java
class StatItem {

    private String name;

    private long lastResetTime;

    private long interval;

    private LongAdder token;

    private int rate;

    StatItem(String name, int rate, long interval) {
        this.name = name;
        this.rate = rate;
        this.interval = interval;
        this.lastResetTime = System.currentTimeMillis();
        this.token = buildLongAdder(rate);
    }
    //是否允许访问，true 为允许
    public boolean isAllowable() {
        long now = System.currentTimeMillis();//记录当前时间毫秒
        //判断最新重置时间和时间间隔是否大于当前时间，即当前时间是否还是最新的时间周期内
        if (now > lastResetTime + interval) {//下一个时间周期
            //重新构造LongAdder，这里不用LongAdder的reset，是因为reset() 需要在没有其他线线程才能成功，而且重置后初始值为0
            //而这里使用总值递减的策略进行tps控制，即tps剩余量控制
            token = buildLongAdder(rate);
            lastResetTime = now;
        }

        if (token.sum() < 0) {//没有可使用的tps量
            return false;
        }
        token.decrement();//递减tps剩余量
        return true;
    }

   ……//省略get/set方法
    @Override
    public String toString() {
        return new StringBuilder(32).append("StatItem ")
                .append("[name=").append(name).append(", ")
                .append("rate = ").append(rate).append(", ")
                .append("interval = ").append(interval).append("]")
                .toString();
    }

    //新建一个LongAdder类：一个或多个变量一起维持初始为long的总和，当多个线程更新时使用，用于诸如收集统计信息，不用于细粒度同步控制的共同总和。
    //LongAdder类也可以使用AtomicLong替代，单他们在高并发下效率不同，LongAdder有更高的并发精度，从而当竞争比较激烈时，预期吞吐量明显高于牺牲更高的空间消耗
    //LongAdder类虽然在精度上没有AtomicLong高，但在较高的并发下，丢失精度换来高吞吐量，用在这里做TPS控制更合适
    private LongAdder buildLongAdder(int rate) {
        LongAdder adder = new LongAdder();
        adder.add(rate);
        return adder;
    }

}
```
StatItem进行统计项的实现，下边我们看一下Dubbo时怎样维护和使用调用方法级别的TPS控制的。DefaultTPSLimiter是TPSLimiter的默认实现，他使用内存存储TPS信息，内部使用效率高。
```java
public class DefaultTPSLimiter implements TPSLimiter {

    private final ConcurrentMap<String, StatItem> stats = new ConcurrentHashMap<String, StatItem>();//存储方法的TPS统计项

    @Override
    public boolean isAllowable(URL url, Invocation invocation) {
        int rate = url.getParameter(TPS_LIMIT_RATE_KEY, -1);//获取TPS频率信息，默认不限
        long interval = url.getParameter(TPS_LIMIT_INTERVAL_KEY, DEFAULT_TPS_LIMIT_INTERVAL);//获取时间间隔长度，默认60s
        String serviceKey = url.getServiceKey();//获取当前服务key
        if (rate > 0) {
            StatItem statItem = stats.get(serviceKey);
            if (statItem == null) {
                stats.putIfAbsent(serviceKey, new StatItem(serviceKey, rate, interval));//初始化statItem
                statItem = stats.get(serviceKey);//获取当前可以的statItem
            } else {
                //当频率和时间间隔已经改变时，重新构建statItem
                if (statItem.getRate() != rate || statItem.getInterval() != interval) {
                    stats.put(serviceKey, new StatItem(serviceKey, rate, interval));
                    statItem = stats.get(serviceKey);
                }
            }
            return statItem.isAllowable();//通过statItem保存的TPS信息判断是否允许访问
        } else {//不限制TPS访问，移除已经保存的TPS信息
            StatItem statItem = stats.get(serviceKey);
            if (statItem != null) {
                stats.remove(serviceKey);
            }
        }

        return true;
    }

}
```
TPS访问控制对我们进行服务保护和降级比较重要，Dubbo对TPS访问控制的实现也比较高效简单，我们可以借鉴使用。