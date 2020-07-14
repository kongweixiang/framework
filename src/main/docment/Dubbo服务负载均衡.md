
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
    // 获取调用者调用的权重，该权重考虑了预热时间，如果正常运行时间在预热时间内，则权重将按比例减少
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
下面是权重的计算过程，该过程主要用于保证当服务运行时长小于服务预热时间时，对服务进行降权，避免让服务在启动之初就处于高负载状态。服务预热是一个优化手段，与此类似的还有 JVM 预热。

AbstractLoadBalance 有很多实现子类
 - RandomLoadBalance：加权随机算法负载均衡
 - LeastActiveLoadBalance 最小活跃数负载均衡
 - ConsistentHashLoadBalance 一致性 hash 算法负载均衡
 - RoundRobinLoadBalance 加权轮询负载均衡
 - ShortestResponseLoadBalance 响应时间最短负载均衡
 
 RandomLoadBalance 是加权随机算法的具体实现，它的算法思想很简单。假设我们有一组服务器 servers = [A, B, C]，他们对应的权重为 weights = [5, 3, 2]，权重总和为10。现在把这些权重值平铺在一维坐标值上，[0, 5) 区间属于服务器 A，[5, 8) 区间属于服务器 B，[8, 10) 区间属于服务器 C。接下来通过随机数生成器生成一个范围在 [0, 10) 之间的随机数，然后计算这个随机数会落到哪个区间上。比如数字3会落到服务器 A 对应的区间上，此时返回服务器 A 即可。权重越大的机器，在坐标轴上对应的区间范围就越大，因此随机数生成器生成的数字就会有更大的概率落到此区间内。  
 下边我们先看 RandomLoadBalance 加权随机算法负载均衡实现：
   1. 如果权重相同，则随机调用
   2. 如果权重不同，则将使用random.nextInt（w1 + w2 + ... + wn）。注意，如果机器的性能优于其他机器，则可以设置更大的权重，性能不好，则设置更小的权重。

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
RoundRobinLoadBalance 加权轮询，根据权重做轮询处理，轮询是一种无状态负载均衡算法，实现简单，适用于每台服务器性能相近的场景下。但现实情况下，我们并不能保证每台服务器性能均相近。因此，这个时候我们需要对轮询过程进行加权，以调控每台服务器的负载。经过加权后，每台服务器能够得到的请求数比例，接近或等于他们的权重比。
```java
//WeightedRoundRobin 内部类，保存服务的权重信息
    protected static class WeightedRoundRobin {
        private int weight;
        private AtomicLong current = new AtomicLong(0);
        private long lastUpdate;

        public int getWeight() {
            return weight;
        }

        public void setWeight(int weight) {
            this.weight = weight;
            current.set(0);
        }

        public long increaseCurrent() {
            return current.addAndGet(weight); //运行时权重，加上配置权重本身
        }

        public void sel(int total) { //允许后权重，减去参与负载均衡的权重总和
            current.addAndGet(-1 * total);
        }

        public long getLastUpdate() {
            return lastUpdate;
        }

        public void setLastUpdate(long lastUpdate) {
            this.lastUpdate = lastUpdate;
        }
    }
    
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        String key = invokers.get(0).getUrl().getServiceKey() + "." + invocation.getMethodName();//获取调用方法签名key：（如UserService.query）
        //获取 key 的 WeightedRoundRobin 信息
        ConcurrentMap<String, WeightedRoundRobin> map = methodWeightMap.computeIfAbsent(key, k -> new ConcurrentHashMap<>());//初始化加权权重信息
        int totalWeight = 0;
        long maxCurrent = Long.MIN_VALUE;//初始化最大权重
        long now = System.currentTimeMillis();
        Invoker<T> selectedInvoker = null;
        WeightedRoundRobin selectedWRR = null;
        for (Invoker<T> invoker : invokers) {
            String identifyString = invoker.getUrl().toIdentityString();
            int weight = getWeight(invoker, invocation);
            //存储 url 唯一标识 identifyString 到 weightedRoundRobin 的映射关系
            WeightedRoundRobin weightedRoundRobin = map.computeIfAbsent(identifyString, k -> {
                WeightedRoundRobin wrr = new WeightedRoundRobin();
                wrr.setWeight(weight);
                return wrr;
            });
            if (weight != weightedRoundRobin.getWeight()) {
                //权重改变时重新设置
                weightedRoundRobin.setWeight(weight);
            }
             // 让 current 加上自身权重，等价于 current += weight
            long cur = weightedRoundRobin.increaseCurrent();
            weightedRoundRobin.setLastUpdate(now);// 设置 lastUpdate，表示近期更新过
            // 找出最大的 current 
            if (cur > maxCurrent) {
                maxCurrent = cur;
                selectedInvoker = invoker;
                selectedWRR = weightedRoundRobin;
            }
            totalWeight += weight; // 计算权重总和
        }
        //除去多余的identifyString 到 weightedRoundRobin 的映射关系
        if (invokers.size() != map.size()) {
            map.entrySet().removeIf(item -> now - item.getValue().getLastUpdate() > RECYCLE_PERIOD);
        }
        if (selectedInvoker != null) {
            selectedWRR.sel(totalWeight);//让 current 减去权重总和，等价于 current -= totalWeight
            return selectedInvoker;
        }
        // 默认返回，代码走不到这儿
        return invokers.get(0);
    }
```

我们可以通过下边简单的表来理解如果对轮询过程进行加权，如果有三台服务[A,B,C]，他们权重设置[1,2,3],下表记录了加权轮询的大体运行信息，即在轮询完一个周期步骤来记录每一步使用的服务和当前权重信息。
| 请求编号 |  当前权重数组 | 选择的服务 | 运行后的权重数组|
|--|--|--|--|
| 1 | [1,3,2] | B | [1,-3,2] |
| 2 | [2,0,4] | C| [2,0,-2] |
| 3 | [3,3,0] | A | [-3,3,0] |
| 4 | [-2,6,2] | B | [-2,0,2] |
| 5 | [-1,3,4] | C | [-1,3,-2] |
| 6 | [0,6,0] | B | [0,0,0] |
| 7 | [1,1,5] | C | [1,1,-2] |
| 1 | [1,1,5] | C | [1,1,-2] |
| 1 | [1,1,5] | C | [1,1,-2] |

ConsistentHashLoadBalance 一致性 hash 算法由麻省理工学院的 Karger 及其合作者于1997年提出的，算法提出之初是用于大规模缓存系统的负载均衡。它的工作过程是这样的，首先根据 ip 或者其他的信息为缓存节点生成一个 hash，并将这个 hash 投射到 [0, 232 - 1] 的圆环上。当有查询或写入请求时，则为缓存项的 key 生成一个 hash 值。然后查找第一个大于或等于该 hash 值的缓存节点，并到这个节点中查询或写入缓存项。如果当前节点挂了，则在下一次查询或写入缓存时，为缓存项查找另一个大于其 hash 值的缓存节点即可。
```java
    private final ConcurrentMap<String, ConsistentHashSelector<?>> selectors = new ConcurrentHashMap<String, ConsistentHashSelector<?>>();

    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        String methodName = RpcUtils.getMethodName(invocation);
        String key = invokers.get(0).getUrl().getServiceKey() + "." + methodName;
       // 获取 invokers 原始的 hashcode
        int invokersHashCode = invokers.hashCode();
        ConsistentHashSelector<T> selector = (ConsistentHashSelector<T>) selectors.get(key);
         // 如果 invokers 是一个新的 List 对象，意味着服务提供者数量发生了变化，可能新增也可能减少了。
         // 此时 selector.identityHashCode != identityHashCode 条件成立
        if (selector == null || selector.identityHashCode != invokersHashCode) {
            selectors.put(key, new ConsistentHashSelector<T>(invokers, methodName, invokersHashCode));
            selector = (ConsistentHashSelector<T>) selectors.get(key);
        }
         // 调用 ConsistentHashSelector 的 select 方法选择 Invoker
        return selector.select(invocation);
    }
   //
    private static final class ConsistentHashSelector<T> {

        private final TreeMap<Long, Invoker<T>> virtualInvokers; // 使用 TreeMap 存储 Invoker 虚拟节点

        private final int replicaNumber;

        private final int identityHashCode;

        private final int[] argumentIndex;

        ConsistentHashSelector(List<Invoker<T>> invokers, String methodName, int identityHashCode) {
            this.virtualInvokers = new TreeMap<Long, Invoker<T>>();
            this.identityHashCode = identityHashCode;
            URL url = invokers.get(0).getUrl();
            this.replicaNumber = url.getMethodParameter(methodName, HASH_NODES, 160);// 获取虚拟节点数，默认为160
            //获取参与计算hash值得参数的下标，默认对第一个参数进行 hash 运算
            String[] index = COMMA_SPLIT_PATTERN.split(url.getMethodParameter(methodName, HASH_ARGUMENTS, "0"));
            argumentIndex = new int[index.length];
            for (int i = 0; i < index.length; i++) {
                argumentIndex[i] = Integer.parseInt(index[i]);
            }
            for (Invoker<T> invoker : invokers) {
                String address = invoker.getUrl().getAddress();
                for (int i = 0; i < replicaNumber / 4; i++) {
                    byte[] digest = md5(address + i);
                     // 对 digest 部分字节进行4次 hash 运算，得到四个不同的 long 型正整数
                    for (int h = 0; h < 4; h++) {
                        long m = hash(digest, h);
                        virtualInvokers.put(m, invoker);//不同位数的hash值放入virtualInvokers 
                    }
                }
            }
        }

        public Invoker<T> select(Invocation invocation) {
            // 将参数转为 key
            String key = toKey(invocation.getArguments());
            // 对参数 key 进行 md5 运算
            byte[] digest = md5(key);
            //取 digest 数组的前四个字节进行 hash 运算，再将 hash 值传给 selectForKey 方法寻找合适的 Invoker
            return selectForKey(hash(digest, 0));
        }

        private String toKey(Object[] args) {
            StringBuilder buf = new StringBuilder();
            for (int i : argumentIndex) {
                if (i >= 0 && i < args.length) {
                    buf.append(args[i]);
                }
            }
            return buf.toString();
        }

        private Invoker<T> selectForKey(long hash) {
            // 到 TreeMap 中查找第一个节点值大于或等于当前 hash 的 Invoker
            Map.Entry<Long, Invoker<T>> entry = virtualInvokers.ceilingEntry(hash);
            //如果 hash 大于 Invoker 在圆环上最大的位置，此时 entry = null需要将 TreeMap 的头节点赋值给 entry
            if (entry == null) {
                entry = virtualInvokers.firstEntry();
            }
            return entry.getValue();
        }

            // h = 0 时，取 digest 中下标为 0 ~ 3 的4个字节进行位运算
            // h = 1 时，取 digest 中下标为 4 ~ 7 的4个字节进行位运算
            // h = 2 时，取 digest 中下标为 8 ~ 11 的4个字节进行位运算
            // h = 3 时，取 digest 中下标为 12 ~ 15 的4个字节进行位运算
        private long hash(byte[] digest, int number) {
            return (((long) (digest[3 + number * 4] & 0xFF) << 24)
                    | ((long) (digest[2 + number * 4] & 0xFF) << 16)
                    | ((long) (digest[1 + number * 4] & 0xFF) << 8)
                    | (digest[number * 4] & 0xFF))
                    & 0xFFFFFFFFL;
        }

        private byte[] md5(String value) {
            MessageDigest md5;
            try {
                md5 = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            md5.reset();
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            md5.update(bytes);
            return md5.digest();
        }

    }
```

ShortestResponseLoadBalance 响应时间最短负载均衡。跟最小活跃数负载均衡类似，选择成功调用响应时间最短的调用者数量：如果只有一个invoke，直接调用，如果有多个权重相同的，则随机调用，如果多个权重不同的，则根据权重调用。
```java
protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        int length = invokers.size();
        //初始化最小请求时间
        long shortestResponse = Long.MAX_VALUE;
        // 最短响应时间的invoke的个数
        int shortestCount = 0;
        // 短响应时间的invoke的坐标
        int[] shortestIndexes = new int[length];
        // 记录权重
        int[] weights = new int[length];
        // 所有最短响应调用者的权重之和
        int totalWeight = 0;
        int firstWeight = 0;
        boolean sameWeight = true;

        // 找出最短时间响应成功的invoke
        for (int i = 0; i < length; i++) {
            Invoker<T> invoker = invokers.get(i);
            //获取invoker URL的统计信息
            RpcStatus rpcStatus = RpcStatus.getStatus(invoker.getUrl(), invocation.getMethodName());
            // 返回根据活动连接与成功的平均经过时间的乘积计算估计的响应时间
            long succeededAverageElapsed = rpcStatus.getSucceededAverageElapsed();
            int active = rpcStatus.getActive();//活跃数
            long estimateResponse = succeededAverageElapsed * active;//响应实际*活跃数 估计请求响应时间
            int afterWarmup = getWeight(invoker, invocation); //获取权重
            weights[i] = afterWarmup;
            //发现更小的响应时间
            if (estimateResponse < shortestResponse) {//第一次发现最小响应请求invoke
                shortestResponse = estimateResponse;
                shortestCount = 1; //重新计数
                shortestIndexes[0] = i;//保存坐标
                totalWeight = afterWarmup;
                firstWeight = afterWarmup;
                sameWeight = true;
            } else if (estimateResponse == shortestResponse) {
                shortestIndexes[shortestCount++] = i; //保存坐标
                totalWeight += afterWarmup; //累计权重
                if (sameWeight && i > 0
                        && afterWarmup != firstWeight) {
                    sameWeight = false; //权重不同
                }
            }
        }
        if (shortestCount == 1) {
            return invokers.get(shortestIndexes[0]); //只有一个，直接返回
        }
        if (!sameWeight && totalWeight > 0) { //发现多个但权重不同，根据权重区间返回
            int offsetWeight = ThreadLocalRandom.current().nextInt(totalWeight);
            for (int i = 0; i < shortestCount; i++) {
                int shortestIndex = shortestIndexes[i];
                offsetWeight -= weights[shortestIndex];
                if (offsetWeight < 0) {
                    return invokers.get(shortestIndex);
                }
            }
        }
        //发现多个权重相同，通过Random随机返回
        return invokers.get(shortestIndexes[ThreadLocalRandom.current().nextInt(shortestCount)]);
    }
```
目前Dubbo中实现了以上5中负载均衡算法，各有偏重点，我们可以根据业务的场景需求选择合适的负载均衡算法使用。