# Dubbo源码解析之负载均衡
Dubbo LoadBalance组件 为负载均衡组件，它的职责是将网络请求，或者其他形式的负载“均摊”到不同的机器上。避免集群中部分服务器压力过大，而另一些服务器比较空闲的情况。通过负载均衡，可以让每台服务器获取到适合自己处理能力的负载。在为高负载服务器分流的同时，还可以避免资源浪费，一举两得。负载均衡可分为软件负载均衡和硬件负载均衡。  
在 Dubbo 中，所有负载均衡实现类均继承自 AbstractLoadBalance，该类实现了 LoadBalance 接口，并封装了一些公共的逻辑。在分析负载均衡实现之前，先来看一下 AbstractLoadBalance 的逻辑。
```java
 public <T> Invoker<T> select(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        if (CollectionUtils.isEmpty(invokers)) {
            return null;
        }
        // 如果 invokers 列表中仅有一个 Invoker，直接返回即可，无需进行负载均衡
        if (invokers.size() == 1) {
            return invokers.get(0);
        }
        // 调用 doSelect 方法进行负载均衡，该方法为抽象方法，由子类实现
        return doSelect(invokers, url, invocation);
    }

    protected abstract <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation);

```
在AbstractLoadBalance，路由的入口实现很简单，主要是交由子类去做真正的路由选择，在AbstractLoadBalance中还有一个功能——权重的获取
```java
/**
     * 获取调用者调用的权重，该权重考虑了预热时间，如果正常运行时间在预热时间内，则权重将按比例减少
     * @param invoker    the invoker
     * @param invocation the invocation of this invoker
     * @return weight
     */
    int getWeight(Invoker<?> invoker, Invocation invocation) {
        int weight;
        URL url = invoker.getUrl();
        // 多注册中心方案中，注册中心的调用实现负载均衡
        if (REGISTRY_SERVICE_REFERENCE_PATH.equals(url.getServiceInterface())) {
            //注册中i性能的负载均衡从url中registry.weight参数获取权重信息，默认权重值100
            weight = url.getParameter(REGISTRY_KEY + "." + WEIGHT_KEY, DEFAULT_WEIGHT);
        } else {
            //其他服务使用weight参数获取权重，默认权重值100
            weight = url.getMethodParameter(invocation.getMethodName(), WEIGHT_KEY, DEFAULT_WEIGHT);
            if (weight > 0) {
                long timestamp = invoker.getUrl().getParameter(TIMESTAMP_KEY, 0L);//获取服务提供者启动时间戳
                if (timestamp > 0L) {
                    long uptime = System.currentTimeMillis() - timestamp;//计算运行时长
                    if (uptime < 0) {
                        return 1;
                    }
                    int warmup = invoker.getUrl().getParameter(WARMUP_KEY, DEFAULT_WARMUP);//获取预热时间
                    if (uptime > 0 && uptime < warmup) {
                        weight = calculateWarmupWeight((int)uptime, warmup, weight);//重新计算预热期间的权重
                    }
                }
            }
        }
        return Math.max(weight, 0);
    }    
    /**
     *根据预热时间的正常运行时间比例计算重量，新的权重在[1,weight]
     */    
    static int calculateWarmupWeight(int uptime, int warmup, int weight) {
        //计算权重，下面代码逻辑上形似于 (uptime / warmup) * weight。
        // 随着服务运行时间 uptime 增大，权重计算值 ww 会慢慢接近配置值 weight
        int ww = (int) ( uptime / ((float) warmup / weight));
        return ww < 1 ? 1 : (Math.min(ww, weight));
    }
```
面是权重的计算过程，该过程主要用于保证当服务运行时长小于服务预热时间时，对服务进行降权，避免让服务在启动之初就处于高负载状态。服务预热是一个优化手段，与此类似的还有 JVM 预热。

AbstractLoadBalance 有很多实现子类
 - RandomLoadBalance：加权随机算法负载均衡
 - LeastActiveLoadBalance 最小活跃数负载均衡
 - ConsistentHashLoadBalance 一致性 hash 算法负载均衡
 - RoundRobinLoadBalance 加权轮询负载均衡
 - ShortestResponseLoadBalance 响应时间最短负载均衡
 
 下边我们先看 RandomLoadBalance 加权随机算法负载均衡实现：
   1. 如果权重相同，则随机调用
   2. 如果权重不同，则将使用random.nextInt（w1 + w2 + ... + wn）。注意，如果机器的性能优于其他机器，则可以设置更大的权重，性能不好，则设置更小的权重。
```java
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // Number of invokers
        int length = invokers.size();
        // Every invoker has the same weight?
        boolean sameWeight = true;
        // the weight of every invokers
        int[] weights = new int[length];
        // the first invoker's weight
        int firstWeight = getWeight(invokers.get(0), invocation);
        weights[0] = firstWeight;
        // The sum of weights
        int totalWeight = firstWeight;
        for (int i = 1; i < length; i++) {
            int weight = getWeight(invokers.get(i), invocation);
            // save for later use
            weights[i] = weight;
            // Sum
            totalWeight += weight;
            if (sameWeight && weight != firstWeight) {
                sameWeight = false;
            }
        }
        if (totalWeight > 0 && !sameWeight) {
            // If (not every invoker has the same weight & at least one invoker's weight>0), select randomly based on totalWeight.
            int offset = ThreadLocalRandom.current().nextInt(totalWeight);
            // Return a invoker based on the random value.
            for (int i = 0; i < length; i++) {
                offset -= weights[i];
                if (offset < 0) {
                    return invokers.get(i);
                }
            }
        }
        // If all invokers have the same weight value or totalWeight=0, return evenly.
        return invokers.get(ThreadLocalRandom.current().nextInt(length));
    }

```   

RandomLoadBalance 是加权随机算法的具体实现，它的算法思想很简单。假设我们有一组服务器 servers = [A, B, C]，他们对应的权重为 weights = [5, 3, 2]，权重总和为10。现在把这些权重值平铺在一维坐标值上，[0, 5) 区间属于服务器 A，[5, 8) 区间属于服务器 B，[8, 10) 区间属于服务器 C。接下来通过随机数生成器生成一个范围在 [0, 10) 之间的随机数，然后计算这个随机数会落到哪个区间上。比如数字3会落到服务器 A 对应的区间上，此时返回服务器 A 即可。权重越大的机器，在坐标轴上对应的区间范围就越大，因此随机数生成器生成的数字就会有更大的概率落到此区间内。
```java
protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // invokers的个数
        int length = invokers.size();
        //是否都是相同权重的判断
        boolean sameWeight = true;
        // 每一个invoke的权重
        int[] weights = new int[length];
        // 第一个invoke的权重
        int firstWeight = getWeight(invokers.get(0), invocation);
        weights[0] = firstWeight;
        // 权重总和
        int totalWeight = firstWeight;
        for (int i = 1; i < length; i++) {
            int weight = getWeight(invokers.get(i), invocation);
            // 权重一维坐标保存
            weights[i] = weight;
            //  加权重总和
            totalWeight += weight;
            if (sameWeight && weight != firstWeight) {
                sameWeight = false;
            }
        }
        if (totalWeight > 0 && !sameWeight) {
            // 如果不是每个invoke的权重都相同或者权重总和为0
            int offset = ThreadLocalRandom.current().nextInt(totalWeight);
            // 根据权重一维坐标区间返回invoke
            for (int i = 0; i < length; i++) {
                offset -= weights[i];
                if (offset < 0) {
                    return invokers.get(i);
                }
            }
        }
        //每个invoke的权重相同或者权重总和为0
        return invokers.get(ThreadLocalRandom.current().nextInt(length));
    }
```
LeastActiveLoadBalance 最小活跃数负载均衡。活跃调用数越小，表明该服务提供者效率越高，单位时间内可处理更多的请求，此时应优先将请求分配给该服务提供者。  
在具体实现中，每个服务提供者对应一个活跃数 active。初始情况下，所有服务提供者活跃数均为0。每收到一个请求，活跃数加1，完成请求后则将活跃数减1。在服务运行一段时间后，性能好的服务提供者处理请求的速度更快，因此活跃数下降的也越快，此时这样的服务提供者能够优先获取到新的服务请求、这就是最小活跃数负载均衡算法的基本思想。
```java
protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // invokers的个数
        int length = invokers.size();
        //最小的活跃数
        int leastActive = -1;
        // 具有相同“最小活跃数”的invoke数量
        int leastCount = 0;
        // leastIndexs 用于记录具有相同“最小活跃数”的 Invoker 在 invokers 列表中的下标信息
        int[] leastIndexes = new int[length];
        //invoke的权重
        int[] weights = new int[length];
        // 第一个最小活跃数的 Invoker 权重值，用于与其他具有相同最小活跃数的 Invoker 的权重进行对比，
        // 以检测是否“所有具有相同最小活跃数的 Invoker 的权重”均相等
        int totalWeight = 0;
        int firstWeight = 0;
        boolean sameWeight = true;
        // 遍历 invokers 列表
        for (int i = 0; i < length; i++) {
            Invoker<T> invoker = invokers.get(i);
            // 获取invoke配置初始活跃数
            int active = RpcStatus.getStatus(invoker.getUrl(), invocation.getMethodName()).getActive();
            // 获取权重，默认值100
            int afterWarmup = getWeight(invoker, invocation);
            weights[i] = afterWarmup;//当前权重
            // 发现更小的活跃数，重新开始
            if (leastActive == -1 || active < leastActive) {
                // 使用当前活跃数 active 更新最小活跃数 leastActive
                leastActive = active;
                //重新设置最小活跃数的个数
                leastCount = 1;
                //记录当前下标值到 leastIndexs 中
                leastIndexes[0] = i;
                //重新设置权重和
                totalWeight = afterWarmup;
                // 记录最小活跃的权重
                firstWeight = afterWarmup;
                sameWeight = true;
                // 当前 Invoker 的活跃数 active 与最小活跃数 leastActive 相同
            } else if (active == leastActive) {
                // 记录相同活最小活跃数的invoke的坐标
                leastIndexes[leastCount++] = i;
                // 计算相同活跃的权重总直
                totalWeight += afterWarmup;
                // 检测当前 Invoker 的权重与 firstWeight 是否相等
                if (sameWeight && afterWarmup != firstWeight) {
                    sameWeight = false;
                }
            }
        }
         //当只有一个 Invoker 具有最小活跃数，此时直接返回该 Invoker 即可
        if (leastCount == 1) {
            return invokers.get(leastIndexes[0]);
        }
        //有多个 Invoker 具有相同的最小活跃数，但它们之间的权重不同
        if (!sameWeight && totalWeight > 0) {
            // 随机生成一个 [0, totalWeight) 之间的数字
            int offsetWeight = ThreadLocalRandom.current().nextInt(totalWeight);
            // 循环让随机数减去具有最小活跃数的 Invoker 的权重值，当当offset 小于等于0时，返回相应的 Invoker
            for (int i = 0; i < leastCount; i++) {
                int leastIndex = leastIndexes[i];
                offsetWeight -= weights[leastIndex];// 获取权重值，并让随机数减去权重值
                if (offsetWeight < 0) {
                    return invokers.get(leastIndex);
                }
            }
        }
         // 如果权重相同或权重为0时，随机返回一个 Invoker
        return invokers.get(leastIndexes[ThreadLocalRandom.current().nextInt(leastCount)]);
    }
```
RoundRobinLoadBalance 