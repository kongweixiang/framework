# Dubbo源码解析之服务集群容错
集群容错包含四个部分，分别是服务目录 Directory、服务路由 Router、集群 Cluster 和负载均衡 LoadBalance。  
集群容错的所有组件  
![在这里插入图片描述](https://img-blog.csdnimg.cn/20200713152504611.jpg?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L2t3eHl6aw==,size_16,color_FFFFFF,t_70#pic_center)
## 服务目录 Directory
服务目录中存储了一些和服务提供者有关的信息，通过服务目录，服务消费者可获取到服务提供者的信息，比如 ip、端口、服务协议等。通过这些信息，服务消费者就可通过 Netty 等客户端进行远程调用。在一个服务集群中，服务提供者数量并不是一成不变的，如果集群中新增了一台机器，相应地在服务目录中就要新增一条服务提供者记录。或者，如果服务提供者的配置修改了，服务目录中的记录也要做相应的更新。  
服务目录目前内置的实现有两个，分别为 StaticDirectory 和 RegistryDirectory，它们均是 AbstractDirectory 的子类。
服务目录入口
```java
public List<Invoker<T>> list(Invocation invocation) throws RpcException {
        if (destroyed) {
            throw new RpcException("Directory already destroyed .url: " + getUrl());
        }

        return doList(invocation);//调用 子类doList 获取 Invoker 列表
    }
```
下边我们分别看一下StaticDirectory和RegistryDirectory的`doList`实现：

```java
//StaticDirectory.java
protected List<Invoker<T>> doList(Invocation invocation) throws RpcException {
        List<Invoker<T>> finalInvokers = invokers;
        if (routerChain != null) {
            try {
                //调用路由规则获取Invoker
                finalInvokers = routerChain.route(getConsumerUrl(), invocation);
            } catch (Throwable t) {
                logger.error("Failed to execute router: " + getUrl() + ", cause: " + t.getMessage(), t);
            }
        }
        return finalInvokers == null ? Collections.emptyList() : finalInvokers;
    }

//RegistryDirectory.java
public List<Invoker<T>> doList(Invocation invocation) {
        if (forbidden) {
            // 1.没有服务提供者 2. 服务被禁用
            throw new RpcException(RpcException.FORBIDDEN_EXCEPTION, "No provider available from registry " +
                    getUrl().getAddress() + " for service " + getConsumerUrl().getServiceKey() + " on consumer " +
                    NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion() +
                    ", please check status of providers(disabled, not registered or in blacklist).");
        }

        if (multiGroup) {
            return this.invokers == null ? Collections.emptyList() : this.invokers;
        }

        List<Invoker<T>> invokers = null;
        try {
            // 从缓存中获取Invoker，运行时才被执行
            invokers = routerChain.route(getConsumerUrl(), invocation);
        } catch (Throwable t) {
            logger.error("Failed to execute router: " + getUrl() + ", cause: " + t.getMessage(), t);
        }

        return invokers == null ? Collections.emptyList() : invokers;
    }        
```
最新的Dubbo中，目录服务做的功能主要是提供服务路由的入口，保存路由规则和发起路由选择的功能，更多的功能已经下沉到路由去做了。

## 服务路由 Router
服务目录在获取 Invoker 列表的过程中，会通过 Router 进行服务路由，筛选出符合路由规则的服务提供者。  
服务路由包含一条路由规则，路由规则决定了服务消费者的调用目标，即规定了服务消费者可调用哪些服务提供者。  
Dubbo 目前提供了好几种服务路由实现，主要有条件路由 ConditionRouter、脚本路由 ScriptRouter 、标签路由 TagRouter和服务动态监听路由ListenableRouter等。
### 条件规则
条件路由规则由两个条件组成，分别用于对服务消费者和提供者进行匹配，条件路由规则的格式如下：[服务消费者匹配条件] => [服务提供者匹配条件]。例如：host = 127.0.1.10 => host = 127.0.1.11 。条件路由规则是一条字符串，对于 Dubbo 来说，它并不能直接理解字符串的意思，需要将其解析成内部格式才行。  
表达式解析：
```java
    public ConditionRouter(URL url) {
        this.url = url;
         // 获取 priority 和 force 配置
        this.priority = url.getParameter(PRIORITY_KEY, 0);
        this.force = url.getParameter(FORCE_KEY, false);
        this.enabled = url.getParameter(ENABLED_KEY, true);
        init(url.getParameterAndDecoded(RULE_KEY));// 获取路由规则
    }

    public void init(String rule) {
        try {
            if (rule == null || rule.trim().length() == 0) {
                throw new IllegalArgumentException("Illegal route rule!");
            }
            rule = rule.replace("consumer.", "").replace("provider.", ""); // 分别获取服务消费者和提供者匹配规则
            int i = rule.indexOf("=>");// 定位 => 分隔符
            // 获取服务消费者和提供者匹配规则
            String whenRule = i < 0 ? null : rule.substring(0, i).trim();
            String thenRule = i < 0 ? rule.trim() : rule.substring(i + 2).trim();
            // 解析服务消费者匹配规则
            Map<String, MatchPair> when = StringUtils.isBlank(whenRule) || "true".equals(whenRule) ? new HashMap<String, MatchPair>() : parseRule(whenRule);
            // 解析服务提供者匹配规则
            Map<String, MatchPair> then = StringUtils.isBlank(thenRule) || "false".equals(thenRule) ? null : parseRule(thenRule);
            // 将解析出的匹配规则分别赋值给 whenCondition 和 thenCondition 成员变量
            this.whenCondition = when;
            this.thenCondition = then;
        } catch (ParseException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    //键值对，存储 match 和 mismatch 添加
    private static final class MatchPair {
        final Set<String> matches = new HashSet<String>();
        final Set<String> mismatches = new HashSet<String>();
    }

    private static Map<String, MatchPair> parseRule(String rule)
            throws ParseException {
        Map<String, MatchPair> condition = new HashMap<String, MatchPair>();
        if (StringUtils.isBlank(rule)) {
            return condition;
        }
        MatchPair pair = null;
        // 多对
        Set<String> values = null;
        final Matcher matcher = ROUTE_PATTERN.matcher(rule); //Pattern.compile("([&!=,]*)\\s*([^&!=,\\s]+)");
          // 通过正则表达式匹配路由规则，ROUTE_PATTERN = ([&!=,]*)\s*([^&!=,\s]+)
          // 这个表达式看起来不是很好理解，第一个括号内的表达式用于匹配"&", "!", "=" 和 "," 等符号。
          // 第二括号内的用于匹配英文字母，数字等字符。举个例子说明一下：
          //    host = 2.2.2.2 & host != 1.1.1.1 & method = hello
          // 匹配结果如下：
          //     括号一      括号二
          // 1.  null       host
          // 2.   =         2.2.2.2
          // 3.   &         host
          // 4.   !=        1.1.1.1 
          // 5.   &         method
          // 6.   =         hello
        while (matcher.find()) { //一个接一个匹配
            String separator = matcher.group(1);
            String content = matcher.group(2);
            // 表达式的开始 获取表达式的host等关键字
            if (StringUtils.isEmpty(separator)) { 
                pair = new MatchPair();
                condition.put(content, pair);//（"host",new MatchPair()）
            }
            // [&!=,]表达式开始 & 表达式,新类型的路由条件
            else if ("&".equals(separator)) {
                if (condition.get(content) == null) {
                    pair = new MatchPair();
                    condition.put(content, pair);
                } else {
                    pair = condition.get(content);
                }
            }
            // "="表达式解析值
            else if ("=".equals(separator)) {
                if (pair == null) {
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());
                }

                values = pair.matches;
                values.add(content);
            }
            // "！="表达式解析值
            else if ("!=".equals(separator)) {
                if (pair == null) {
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());
                }

                values = pair.mismatches;
                values.add(content);
            }
            // 上一次表达式的","分割的多个值
            else if (",".equals(separator)) {
                if (values == null || values.isEmpty()) {
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());
                }
                values.add(content);
            } else {
                throw new ParseException("Illegal route rule \"" + rule
                        + "\", The error char '" + separator + "' at index "
                        + matcher.start() + " before \"" + content + "\".", matcher.start());
            }
        }
        return condition;
    }
   
```
服务路由的入口方法是 ConditionRouter 的 route 方法，该方法定义在 Router 接口中。实现代码如下：
```java
    public <T> List<Invoker<T>> route(List<Invoker<T>> invokers, URL url, Invocation invocation)
            throws RpcException {
        if (!enabled) {
            return invokers;
        }
        if (CollectionUtils.isEmpty(invokers)) {
            return invokers;
        }
        try {
            if (!matchWhen(url, invocation)) {
                return invokers;
            }
            List<Invoker<T>> result = new ArrayList<Invoker<T>>();
            if (thenCondition == null) {
                logger.warn("The current consumer in the service blacklist. consumer: " + NetUtils.getLocalHost() + ", service: " + url.getServiceKey());
                return result;
            }
            for (Invoker<T> invoker : invokers) {
                if (matchThen(invoker.getUrl(), url)) {
                    result.add(invoker);
                }
            }
            if (!result.isEmpty()) {
                return result;
            } else if (force) {
                logger.warn("The route result is empty and force execute. consumer: " + NetUtils.getLocalHost() + ", service: " + url.getServiceKey() + ", router: " + url.getParameterAndDecoded(RULE_KEY));
                return result;
            }
        } catch (Throwable t) {
            logger.error("Failed to execute condition router rule: " + getUrl() + ", invokers: " + invokers + ", cause: " + t.getMessage(), t);
        }
        return invokers;
    }

    //服务消费者配条件匹配
    boolean matchWhen(URL url/** 消费者配置信息*/, Invocation invocation) {
        return CollectionUtils.isEmptyMap(whenCondition) || matchCondition(whenCondition, url, null, invocation);//通过whenCondition匹配
    }

    //服务提供者配条件匹配
    private boolean matchThen(URL url /** 提供者配置信息*/, URL param /** 消费者配置信息*/) {
        return CollectionUtils.isNotEmptyMap(thenCondition) && matchCondition(thenCondition, url, param, null);//通过thenCondition匹配
    }

    private boolean matchCondition(Map<String, MatchPair> condition, URL url, URL param, Invocation invocation) {
        Map<String, String> sample = url.toMap();
        boolean result = false;
        for (Map.Entry<String, MatchPair> matchPair : condition.entrySet()) {
            String key = matchPair.getKey();
            String sampleValue;
            if (invocation != null && (METHOD_KEY.equals(key) || METHODS_KEY.equals(key))) {
                sampleValue = invocation.getMethodName();//从 invocation 中获取调用方法
            } else if (ADDRESS_KEY.equals(key)) {//从 提供者url 中消费者获取地址
                sampleValue = url.getAddress();
            } else if (HOST_KEY.equals(key)) {//从 提供者url 中获取host主机ip
                sampleValue = url.getHost();
            } else {
                sampleValue = sample.get(key);
                if (sampleValue == null) {
                    sampleValue = sample.get(key);
                }
            }
            if (sampleValue != null) {
                if (!matchPair.getValue().isMatch(sampleValue, param)) { //匹配
                    return false;
                } else {
                    result = true;
                }
            } else {
                //如果不包含条件判断参数
                if (!matchPair.getValue().matches.isEmpty()) {//如果有条件判断，则判断为失败
                    return false;
                } else {
                    result = true;
                }
            }
        }
        return result;
    }

    protected static final class MatchPair {
        final Set<String> matches = new HashSet<String>();
        final Set<String> mismatches = new HashSet<String>();

        private boolean isMatch(String value, URL param) {
            if (!matches.isEmpty() && mismatches.isEmpty()) {//只有matches条件
                for (String match : matches) {
                    if (UrlUtils.isMatchGlobPattern(match, value, param)) {
                        return true;
                    }
                }
                return false;
            }
            if (!mismatches.isEmpty() && matches.isEmpty()) { //只有mismatches条件
                for (String mismatch : mismatches) {
                    if (UrlUtils.isMatchGlobPattern(mismatch, value, param)) {
                        return false;
                    }
                }
                return true;
            }
            if (!matches.isEmpty() && !mismatches.isEmpty()) { //matches和mismatches条件同事存在
                //优先使用 mismatches 条件判断
                for (String mismatch : mismatches) {
                    if (UrlUtils.isMatchGlobPattern(mismatch, value, param)) {
                        return false;
                    }
                }
                for (String match : matches) {
                    if (UrlUtils.isMatchGlobPattern(match, value, param)) {
                        return true;
                    }
                }
                return false;
            }
            return false;
        }
    }

    public static boolean isMatchGlobPattern(String pattern, String value, URL param) {
        if (param != null && pattern.startsWith("$")) {
            pattern = param.getRawParameter(pattern.substring(1));
        }
        return isMatchGlobPattern(pattern, value);
    }

    public static boolean isMatchGlobPattern(String pattern, String value) {
        if ("*".equals(pattern)) {
            return true;
        }
        if (StringUtils.isEmpty(pattern) && StringUtils.isEmpty(value)) {
            return true;
        }
        if (StringUtils.isEmpty(pattern) || StringUtils.isEmpty(value)) {
            return false;
        }
        int i = pattern.lastIndexOf('*');
        // 没有发现 "*"
        if (i == -1) {
            return value.equals(pattern);
        }
        // "*" 在最后面
        else if (i == pattern.length() - 1) {
            return value.startsWith(pattern.substring(0, i));
        }
        //"*" 在最前面
        else if (i == 0) {
            return value.endsWith(pattern.substring(i + 1));
        }
        // "*"  在中间
        else {
            String prefix = pattern.substring(0, i);
            String suffix = pattern.substring(i + 1);
            return value.startsWith(prefix) && value.endsWith(suffix);
        }
    }
```
这些就是条件判断的大体内容。其他的路由规则在这儿就不做介绍。
## 集群 Cluster 
为了避免单点故障，现在的应用通常至少会部署在两台服务器上。对于一些负载比较高的服务，会部署更多的服务器。这样，在同一环境下的服务提供者数量会大于1。对于服务消费者来说，同一环境下出现了多个服务提供者。这时会出现一个问题，服务消费者需要决定选择哪个服务提供者进行调用。另外服务调用失败时的处理措施也是需要考虑的，是重试呢，还是抛出异常，亦或是只打印异常等。为了处理这些问题，Dubbo 定义了集群接口 Cluster 以及 Cluster Invoker。集群 Cluster 用途是将多个服务提供者合并为一个 Cluster Invoker，并将这个 Invoker 暴露给服务消费者。
Dubbo 主要提供了这样几种容错方式：  
- Failover Cluster - 失败自动切换
- Failfast Cluster - 快速失败
- Failsafe Cluster - 失败安全
- Failback Cluster - 失败自动恢复
- Forking Cluster - 并行调用多个服务提供者
- Mergeable Cluster - 多结果调用合并  

下面开始分析源码：
```java
    public class FailoverCluster extends AbstractCluster {
    
        public final static String NAME = "failover";
    
        @Override
        public <T> AbstractClusterInvoker<T> doJoin(Directory<T> directory) throws RpcException {
            return new FailoverClusterInvoker<>(directory);
        }
    
    }

    public class FailsafeCluster extends AbstractCluster {
    
        public final static String NAME = "failsafe";
    
        @Override
        public <T> AbstractClusterInvoker<T> doJoin(Directory<T> directory) throws RpcException {
            return new FailsafeClusterInvoker<>(directory);
        }
    
    }
```
AbstractCluster的实现都比较简单，Cluster的工作主要交给AbstractClusterInvoker的子类去做,入口为Invoke方法，下边我们看看失败自动切换的FailoverClusterInvoker
```java
//AbstractClusterInvoker
    public Result invoke(final Invocation invocation) throws RpcException {
        checkWhetherDestroyed();
        //绑定 attachments 到 invocation.
        Map<String, Object> contextAttachments = RpcContext.getContext().getObjectAttachments();
        if (contextAttachments != null && contextAttachments.size() != 0) {
            ((RpcInvocation) invocation).addObjectAttachments(contextAttachments);
        }
        List<Invoker<T>> invokers = list(invocation); //directory.list(invocation); 通过服务获取
        LoadBalance loadbalance = initLoadBalance(invokers, invocation); //负载均衡
        RpcUtils.attachInvocationIdIfAsync(getUrl(), invocation);
        return doInvoke(invocation, invokers, loadbalance); //调用
    }

//FailoverClusterInvoker的实现
    public Result doInvoke(Invocation invocation, final List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        List<Invoker<T>> copyInvokers = invokers;
        checkInvokers(copyInvokers, invocation);
        String methodName = RpcUtils.getMethodName(invocation);//得到调用方法的名字
        int len = getUrl().getMethodParameter(methodName, RETRIES_KEY, DEFAULT_RETRIES) + 1;//获取重试配置次数
        if (len <= 0) {
            len = 1;
        }
        // 重试
        RpcException le = null; // 保存最新的调用异常.
        List<Invoker<T>> invoked = new ArrayList<Invoker<T>>(copyInvokers.size()); //保存已经调用的invoke.
        Set<String> providers = new HashSet<String>(len);
        for (int i = 0; i < len; i++) {
            //为了避免`invokers`中条件变化，重试时需要重新选择合法的invokers.
            //NOTE: if `invokers` changed, then `invoked` also lose accuracy.
            if (i > 0) {
                checkWhetherDestroyed();
                copyInvokers = list(invocation);
                // 再次检查
                checkInvokers(copyInvokers, invocation);
            }
            Invoker<T> invoker = select(loadbalance, invocation, copyInvokers, invoked);//调用模板select
            invoked.add(invoker);//调用过的invoke记录
            RpcContext.getContext().setInvokers((List) invoked);
            try {
                Result result = invoker.invoke(invocation);//invoke调用服务
                if (le != null && logger.isWarnEnabled()) {
                    logger.warn("Although retry the method " + methodName
                            + " in the service " + getInterface().getName()
                            + " was successful by the provider " + invoker.getUrl().getAddress()
                            + ", but there have been failed providers " + providers
                            + " (" + providers.size() + "/" + copyInvokers.size()
                            + ") from the registry " + directory.getUrl().getAddress()
                            + " on the consumer " + NetUtils.getLocalHost()
                            + " using the dubbo version " + Version.getVersion() + ". Last error is: "
                            + le.getMessage(), le);
                }
                return result;
            } catch (RpcException e) {
                if (e.isBiz()) { // biz exception.
                    throw e;
                }
                le = e;
            } catch (Throwable e) {
                le = new RpcException(e.getMessage(), e);
            } finally {
                providers.add(invoker.getUrl().getAddress());
            }
        }
        throw new RpcException(le.getCode(), "Failed to invoke the method "
                + methodName + " in the service " + getInterface().getName()
                + ". Tried " + len + " times of the providers " + providers
                + " (" + providers.size() + "/" + copyInvokers.size()
                + ") from the registry " + directory.getUrl().getAddress()
                + " on the consumer " + NetUtils.getLocalHost() + " using the dubbo version "
                + Version.getVersion() + ". Last error is: "
                + le.getMessage(), le.getCause() != null ? le.getCause() : le);
    }
```
AbstractClusterInvoker的子类在调用选择时，通过模板`list(Invocation invocation)`重新调用
```java
    protected Invoker<T> select(LoadBalance loadbalance, Invocation invocation,
                                List<Invoker<T>> invokers, List<Invoker<T>> selected) throws RpcException {
        if (CollectionUtils.isEmpty(invokers)) {
            return null;
        }
        // 获取调用方法名
        String methodName = invocation == null ? StringUtils.EMPTY_STRING : invocation.getMethodName();
        //获取 sticky 配置，sticky 表示粘滞连接。所谓粘滞连接是指让服务消费者尽可能的调用同一个服务提供者，除非该提供者挂了再进行切换
        boolean sticky = invokers.get(0).getUrl()
                .getMethodParameter(methodName, CLUSTER_STICKY_KEY, DEFAULT_CLUSTER_STICKY);
        // 检测 invokers 列表是否包含 stickyInvoker，如果不包含说明 stickyInvoker 代表的服务提供者挂了，此时需要将其置空
        if (stickyInvoker != null && !invokers.contains(stickyInvoker)) {
            stickyInvoker = null;
        }
        //在 sticky 为 true，且 stickyInvoker != null 的情况下。如果 selected 包含 stickyInvoker表明 stickyInvoker 对应的服务提供者可能因网络原因未能成功提供服务。
        // 但是该提供者并没挂，此时 invokers 列表中仍存在该服务提供者对应的 Invoker。
        if (sticky && stickyInvoker != null && (selected == null || !selected.contains(stickyInvoker))) {
            //availablecheck 表示是否开启了可用性检查，如果开启了，则调用 stickyInvoker 的 sAvailable 方法进行检查，如果检查通过，则直接返回 stickyInvoker。
            if (availablecheck && stickyInvoker.isAvailable()) {
                return stickyInvoker;
            }
        }
        //如果stickyInvoker为空或者不可用，此时继续调用 doSelect 选择 Invoker
        Invoker<T> invoker = doSelect(loadbalance, invocation, invokers, selected);
        if (sticky) {// 如果 sticky 为 true，则将负载均衡组件选出的 invoker 赋值给 stickyInvoker
            stickyInvoker = invoker;
        }
        return invoker;
    }

    private Invoker<T> doSelect(LoadBalance loadbalance, Invocation invocation,
                                List<Invoker<T>> invokers, List<Invoker<T>> selected) throws RpcException {
        if (CollectionUtils.isEmpty(invokers)) {
            return null;
        }
        if (invokers.size() == 1) {
            return invokers.get(0);
        }
        Invoker<T> invoker = loadbalance.select(invokers, getUrl(), invocation);//通过负载均衡选择invoker

        //如果 选择出来的invoker 是已经选择过的，或者invoker不可用，重新选择
        if ((selected != null && selected.contains(invoker))
                || (!invoker.isAvailable() && getUrl() != null && availablecheck)) {
            try {
                Invoker<T> rInvoker = reselect(loadbalance, invocation, invokers, selected, availablecheck);
                if (rInvoker != null) {
                    invoker = rInvoker;   // 如果 rinvoker 不为空，则将其赋值给 invoker
                } else {
                    //检查选中的invoker是否是最后一个，如果不是，则继续选择下一个
                    int index = invokers.indexOf(invoker);
                    try {
                        //避免选择碰撞，即每次都能选择到下一个而不超出invokers
                        invoker = invokers.get((index + 1) % invokers.size());
                    } catch (Exception e) {
                        logger.warn(e.getMessage() + " may because invokers list dynamic change, ignore.", e);
                    }
                }
            } catch (Throwable t) {
                logger.error("cluster reselect fail reason is :" + t.getMessage() + " if can not solve, you can set cluster.availablecheck=false in url", t);
            }
        }
        return invoker;
    }

    private Invoker<T> reselect(LoadBalance loadbalance, Invocation invocation,
                                List<Invoker<T>> invokers, List<Invoker<T>> selected, boolean availablecheck) throws RpcException {
        //保存可以参与选择的
        List<Invoker<T>> reselectInvokers = new ArrayList<>(
                invokers.size() > 1 ? (invokers.size() - 1) : invokers.size());
        //检测可用性
        for (Invoker<T> invoker : invokers) {
            if (availablecheck && !invoker.isAvailable()) {
                continue;
            }
            //去除已使用过的invoker
            if (selected == null || !selected.contains(invoker)) {
                reselectInvokers.add(invoker);
            }
        }
        if (!reselectInvokers.isEmpty()) {
            return loadbalance.select(reselectInvokers, getUrl(), invocation);
        }
        // 如果前面reselectInvokers为空，则将selected的可用的也加入重新选择
        if (selected != null) {
            for (Invoker<T> invoker : selected) {
                if ((invoker.isAvailable()) // 检测可用性
                        && !reselectInvokers.contains(invoker)) {
                    reselectInvokers.add(invoker);
                }
            }
        }
        if (!reselectInvokers.isEmpty()) {
            return loadbalance.select(reselectInvokers, getUrl(), invocation);//负载均衡组件选择出invoker
        }
        return null;
    }
```
这样，一个Cluster选择invoker就已经完成，下面我们再看看其他的。  
FailbackClusterInvoker 会在调用失败后，返回一个空结果给服务消费者。并通过定时任务对失败的调用进行重传，适合执行消息通知等操作。
```java
//FailbackClusterInvoker
    protected Result doInvoke(Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        Invoker<T> invoker = null;
        try {
            checkInvokers(invokers, invocation);
            // 选择 Invoker
            invoker = select(loadbalance, invocation, invokers, null);
            return invoker.invoke(invocation);
        } catch (Throwable e) {
            logger.error("Failback to invoke method " + invocation.getMethodName() + ", wait for retry in background. Ignored exception: "
                    + e.getMessage() + ", ", e);
            addFailed(loadbalance, invocation, invokers, invoker);如果失败重传
            return AsyncRpcResult.newDefaultAsyncResult(null, null, invocation); // 返回液一个AsyncRpcResult异步结果
        }
    }
    private void addFailed(LoadBalance loadbalance, Invocation invocation, List<Invoker<T>> invokers, Invoker<T> lastInvoker) {
        if (failTimer == null) {
            synchronized (this) {
                if (failTimer == null) {
                    failTimer = new HashedWheelTimer(
                            new NamedThreadFactory("failback-cluster-timer", true),
                            1,
                            TimeUnit.SECONDS, 32, failbackTasks);
                }
            }
        }
        RetryTimerTask retryTimerTask = new RetryTimerTask(loadbalance, invocation, invokers, lastInvoker, retries, RETRY_FAILED_PERIOD);
        try {
            failTimer.newTimeout(retryTimerTask, RETRY_FAILED_PERIOD, TimeUnit.SECONDS);
        } catch (Throwable e) {
            logger.error("Failback background works error,invocation->" + invocation + ", exception: " + e.getMessage());
        }
    }
   //retryTimerTask 的调用
    public void run(Timeout timeout) {
            try {
                Invoker<T> retryInvoker = select(loadbalance, invocation, invokers, Collections.singletonList(lastInvoker));
                lastInvoker = retryInvoker;
                retryInvoker.invoke(invocation);
            } catch (Throwable e) {
                logger.error("Failed retry to invoke method " + invocation.getMethodName() + ", waiting again.", e);
                if ((++retryTimes) >= retries) {
                    logger.error("Failed retry times exceed threshold (" + retries + "), We have to abandon, invocation->" + invocation);
                } else {
                    rePut(timeout);
                }
            }
        }
```
FailfastClusterInvoker 只会进行一次调用，失败后立即抛出异常。适用于幂等操作，比如新增记录。
```java
    public Result doInvoke(Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        checkInvokers(invokers, invocation);
        Invoker<T> invoker = select(loadbalance, invocation, invokers, null);
        try {
            return invoker.invoke(invocation);
        } catch (Throwable e) {
            if (e instanceof RpcException && ((RpcException) e).isBiz()) { // biz exception.
                throw (RpcException) e;
            }
            throw new RpcException(e instanceof RpcException ? ((RpcException) e).getCode() : 0,
                    "Failfast invoke providers " + invoker.getUrl() + " " + loadbalance.getClass().getSimpleName()
                            + " select from all providers " + invokers + " for service " + getInterface().getName()
                            + " method " + invocation.getMethodName() + " on consumer " + NetUtils.getLocalHost()
                            + " use dubbo version " + Version.getVersion()
                            + ", but no luck to perform the invocation. Last error is: " + e.getMessage(),
                    e.getCause() != null ? e.getCause() : e);
        }
    }
```
BroadcastClusterInvoker 广播调用，会给所有的客户端都调用
```java
    public Result doInvoke(final Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        checkInvokers(invokers, invocation);
        RpcContext.getContext().setInvokers((List) invokers);
        RpcException exception = null; //记录最新的异常
        Result result = null;
        for (Invoker<T> invoker : invokers) {//调用所有的invoker
            try {
                result = invoker.invoke(invocation);
            } catch (RpcException e) {
                exception = e;
                logger.warn(e.getMessage(), e);
            } catch (Throwable e) {
                exception = new RpcException(e.getMessage(), e);
                logger.warn(e.getMessage(), e);
            }
        }
        if (exception != null) {
            throw exception;
        }
        return result;
    }
```
FailsafeClusterInvoker 是一种失败安全的 Cluster Invoker。所谓的失败安全是指，当调用过程中出现异常时，FailsafeClusterInvoker 仅会打印异常，而不会抛出异常。适用于写入审计日志等操作。
```java
public Result doInvoke(Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        try {
            checkInvokers(invokers, invocation);
            Invoker<T> invoker = select(loadbalance, invocation, invokers, null);
            return invoker.invoke(invocation);
        } catch (Throwable e) {
            logger.error("Failsafe ignore exception: " + e.getMessage(), e);
            return AsyncRpcResult.newDefaultAsyncResult(null, null, invocation); // ignore
        }
    }
```

ForkingClusterInvoker 会在运行时通过线程池创建多个线程，并发调用多个服务提供者。只要有一个服务提供者成功返回了结果，doInvoke 方法就会立即结束运行。
```java
public Result doInvoke(final Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        try {
            checkInvokers(invokers, invocation);
            final List<Invoker<T>> selected;
            final int forks = getUrl().getParameter(FORKS_KEY, DEFAULT_FORKS);//获取 forks 配置
            final int timeout = getUrl().getParameter(TIMEOUT_KEY, DEFAULT_TIMEOUT);//获取超时配置
            if (forks <= 0 || forks >= invokers.size()) {
                selected = invokers;
            } else {
                selected = new ArrayList<>(forks);
                while (selected.size() < forks) {
                    Invoker<T> invoker = select(loadbalance, invocation, invokers, selected);
                    if (!selected.contains(invoker)) {
                         // 循环选出 forks 个 Invoker，并添加到 selected 中
                        selected.add(invoker);
                    }
                }
            }
            RpcContext.getContext().setInvokers((List) selected);
            final AtomicInteger count = new AtomicInteger();
            final BlockingQueue<Object> ref = new LinkedBlockingQueue<>();
            for (final Invoker<T> invoker : selected) { // 遍历 selected 列表
                // 为每个 Invoker 创建一个执行线程
                executor.execute(() -> {
                    try {
                        Result result = invoker.invoke(invocation); // 进行远程调用
                        ref.offer(result);// 将结果存到阻塞队列中
                    } catch (Throwable e) {
                        int value = count.incrementAndGet();
                        //仅在 value 大于等于 selected.size() 时，将异常对象阻塞队列中，如果前面都执行失败了，最后返回异常行信息
                        if (value >= selected.size()) {
                            ref.offer(e);
                        }
                    }
                });
            }
            try {
                Object ret = ref.poll(timeout, TimeUnit.MILLISECONDS);
                if (ret instanceof Throwable) {
                    Throwable e = (Throwable) ret;
                    throw new RpcException(e instanceof RpcException ? ((RpcException) e).getCode() : 0, "Failed to forking invoke provider " + selected + ", but no luck to perform the invocation. Last error is: " + e.getMessage(), e.getCause() != null ? e.getCause() : e);
                }
                return (Result) ret;
            } catch (InterruptedException e) {
                throw new RpcException("Failed to forking invoke provider " + selected + ", but no luck to perform the invocation. Last error is: " + e.getMessage(), e);
            }
        } finally {
            RpcContext.getContext().clearAttachments();
        }
    }
```
MergeableClusterInvoker 合并结果，可以按照返回对象的中的合并方法合并结果
```java
protected Result doInvoke(Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        checkInvokers(invokers, invocation);
        String merger = getUrl().getMethodParameter(invocation.getMethodName(), MERGER_KEY);//获取merger配置
        if (ConfigUtils.isEmpty(merger)) { // 如果不存在合并配置，则使用普通调用
            for (final Invoker<T> invoker : invokers) {
                if (invoker.isAvailable()) {
                    try {
                        return invoker.invoke(invocation);//普通调用
                    } catch (RpcException e) {
                        if (e.isNoInvokerAvailableAfterFilter()) {
                            log.debug("No available provider for service" + getUrl().getServiceKey() + " on group " + invoker.getUrl().getParameter(GROUP_KEY) + ", will continue to try another group.");
                        } else {
                            throw e;
                        }
                    }
                }
            }
            return invokers.iterator().next().invoke(invocation);//普通调用
        }
        Class<?> returnType;
        try {
            //获取接口返回类型
            returnType = getInterface().getMethod(
                    invocation.getMethodName(), invocation.getParameterTypes()).getReturnType();
        } catch (NoSuchMethodException e) {
            returnType = null;
        }
        Map<String, Result> results = new HashMap<>();
        //循环异步调用所有的invoker
        for (final Invoker<T> invoker : invokers) {
            RpcInvocation subInvocation = new RpcInvocation(invocation, invoker);
            subInvocation.setAttachment(ASYNC_KEY, "true");
            results.put(invoker.getUrl().getServiceKey(), invoker.invoke(subInvocation));
        }
        Object result = null;
        //获取所有的调用结果
        List<Result> resultList = new ArrayList<Result>(results.size());
        for (Map.Entry<String, Result> entry : results.entrySet()) {
            Result asyncResult = entry.getValue();
            try {
                Result r = asyncResult.get();
                if (r.hasException()) {
                    log.error("Invoke " + getGroupDescFromServiceKey(entry.getKey()) +
                                    " failed: " + r.getException().getMessage(),
                            r.getException());
                } else {
                    resultList.add(r);
                }
            } catch (Exception e) {
                throw new RpcException("Failed to invoke service " + entry.getKey() + ": " + e.getMessage(), e);
            }
        }

        if (resultList.isEmpty()) {
            return AsyncRpcResult.newDefaultAsyncResult(invocation); //返回一个默认的结果
        } else if (resultList.size() == 1) {
            return resultList.iterator().next(); //返回结果
        }
        if (returnType == void.class) { //如果是void返回类型
            return AsyncRpcResult.newDefaultAsyncResult(invocation); //通过invocation构造结果返回
        }
        if (merger.startsWith(".")) { //如果返回方法前有"."，表示需要使用指定的合并方法
            merger = merger.substring(1);
            Method method;
            try {
                method = returnType.getMethod(merger, returnType);//获取合并方法
            } catch (NoSuchMethodException e) {
                throw new RpcException("Can not merge result because missing method [ " + merger + " ] in class [ " +
                        returnType.getName() + " ]");
            }
            if (!Modifier.isPublic(method.getModifiers())) {
                method.setAccessible(true);
            }
            result = resultList.remove(0).getValue();//获取第一个结果
            try {
                {//如果合并方法返回类型和结果类型一致，则使用合并结果的去跟下一个结果调用合并方法合并结果
                if (method.getReturnType() != void.class
                        && method.getReturnType().isAssignableFrom(result.getClass())) 
                    for (Result r : resultList) {
                        result = method.invoke(result, r.getValue());
                    }
                } else {//否则，其他结果依次和第一个结果调用合并方法
                    for (Result r : resultList) {
                        method.invoke(result, r.getValue());//依次用第一个结果与后边的结果调用合并方法合并结果
                    }
                }
            } catch (Exception e) {
                throw new RpcException("Can not merge result: " + e.getMessage(), e);
            }
        } else {
           //如果不存在合并饭发，使用合并服务合并
            Merger resultMerger;
            if (ConfigUtils.isDefault(merger)) {
                resultMerger = MergerFactory.getMerger(returnType);
            } else {
                resultMerger = ExtensionLoader.getExtensionLoader(Merger.class).getExtension(merger);
            }
            if (resultMerger != null) {
                List<Object> rets = new ArrayList<Object>(resultList.size());
                for (Result r : resultList) {
                    rets.add(r.getValue());
                }
                result = resultMerger.merge(
                        rets.toArray((Object[]) Array.newInstance(returnType, 0)));//使用合并服务合并结果集
            } else {
                throw new RpcException("There is no merger to merge result.");
            }
        }
        return AsyncRpcResult.newDefaultAsyncResult(result, invocation);
    }
```