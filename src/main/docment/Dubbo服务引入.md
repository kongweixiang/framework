
#Dubbo服务引用
在Dubbo中提供者负责服务的导出和发布，而消费着负责订阅服务和服务的导入。在 Dubbo 中，我们可以通过两种方式引用远程服务。第一种是使用服务直连的方式引用服务，第二种方式是基于注册中心进行引用。  
Dubbo 服务引用的时机有两个，第一个是在 Spring 容器调用 ReferenceBean 的 afterPropertiesSet 方法时引用服务，第二个是在 ReferenceBean 对应的服务被注入到其他类中时引用。这两个引用服务的时机区别在于，第一个是饿汉式的，第二个是懒汉式的。默认情况下，Dubbo 使用懒汉式引用服务。  
下边是官网的一个服务引用的时序图:
![在这里插入图片描述](https://img-blog.csdnimg.cn/20200711170928586.jpg?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L2t3eHl6aw==,size_16,color_FFFFFF,t_70#pic_center)
源码解析：
```java
public void afterPropertiesSet() throws Exception {
        // 初始化dubbo的配置
        prepareDubboConfigBeans();
        // 默认使用懒汉加载
        if (init == null) {
            init = false;
        }
        // 饿汉加载，即时引入服务
        if (shouldInit()) {
            getObject();
        }
    }    

	public Object getObject() {
        return get();
    }
public synchronized T get() {
        if (destroyed) {
            throw new IllegalStateException("The invoker of ReferenceConfig(" + url + ") has already destroyed!");
        }
        if (ref == null) {
            init(); //启动初始化操作
        }
        return ref;
    }
```
在进行具体工作之前，需先进行配置检查与收集工作。接着根据收集到的信息决定服务用的方式，有三种，第一种是引用本地 (JVM) 服务，第二是通过直连方式引用远程服务，第三是通过注册中心引用远程服务。
```java
public synchronized void init() {
        if (initialized) {//避免重复加载
            return;
        }
        //获取Dubbo核心容器
        if (bootstrap == null) {
            bootstrap = DubboBootstrap.getInstance(); 
            bootstrap.init(); //进行Dubbo核心配置的加载和检查
        }
        //在对象创建后在使用其他配置模块配置对象之前检查对象配置并重写默认配置
        checkAndUpdateSubConfigs(); 
        //检查并生成sub配置和Local配置是否合法
        checkStubAndLocal(interfaceClass);
        //判断对象是否有mock并生成mock信息
        ConfigValidationUtils.checkMock(interfaceClass, this);
        //保存对象属性map信息
        Map<String, String> map = new HashMap<String, String>();
        map.put(SIDE_KEY, CONSUMER_SIDE); //添加sid_key
        //添加版本信息，包含dubbo版本，release版本，timestamp运行时间戳和sid_key等信息
        ReferenceConfigBase.appendRuntimeParameters(map);
        //添加泛型 revision信息
        if (!ProtocolUtils.isGeneric(generic)) {
            String revision = Version.getVersion(interfaceClass, version);
            if (revision != null && revision.length() > 0) {
                map.put(REVISION_KEY, revision);
            }
            //生成服务的代理对象，跟服务导出是一样，通过代理对象来代理，返回代理方法
            String[] methods = Wrapper.getWrapper(interfaceClass).getMethodNames();
            if (methods.length == 0) {
                logger.warn("No method found in service interface " + interfaceClass.getName());
                map.put(METHODS_KEY, ANY_VALUE);
            } else {//添加需要代理的方法
                map.put(METHODS_KEY, StringUtils.join(new HashSet<String>(Arrays.asList(methods)), COMMA_SEPARATOR));
            }
        }
        map.put(INTERFACE_KEY, interfaceName);//添加interface名
        AbstractConfig.appendParameters(map, getMetrics());//添加重试信息
        AbstractConfig.appendParameters(map, getApplication());//检查获取并添加Application信息
        AbstractConfig.appendParameters(map, getModule());//检查获取并添加Module信息
        AbstractConfig.appendParameters(map, consumer);//检查获取并添加consumer信息
        AbstractConfig.appendParameters(map, this);
        //设置方法重试信息并收集方法异步调用信息
        Map<String, AsyncMethodInfo> attributes = null;
        if (CollectionUtils.isNotEmpty(getMethods())) {
            attributes = new HashMap<>();
            for (MethodConfig methodConfig : getMethods()) {
                AbstractConfig.appendParameters(map, methodConfig, methodConfig.getName());
                String retryKey = methodConfig.getName() + ".retry";
                if (map.containsKey(retryKey)) {
                    String retryValue = map.remove(retryKey);
                    if ("false".equals(retryValue)) {
                        map.put(methodConfig.getName() + ".retries", "0");
                    }
                }
                AsyncMethodInfo asyncMethodInfo = AbstractConfig.convertMethodConfig2AsyncInfo(methodConfig);
                if (asyncMethodInfo != null) {
                    attributes.put(methodConfig.getName(), asyncMethodInfo);
                }
            }
        }
        //获取服务消费者 ip 地址
        String hostToRegistry = ConfigUtils.getSystemProperty(DUBBO_IP_TO_REGISTRY);
        if (StringUtils.isEmpty(hostToRegistry)) {
            hostToRegistry = NetUtils.getLocalHost();
        } else if (isInvalidLocalHost(hostToRegistry)) {
            throw new IllegalArgumentException("Specified invalid registry ip from property:" + DUBBO_IP_TO_REGISTRY + ", value:" + hostToRegistry);
        }
        map.put(REGISTER_IP_KEY, hostToRegistry);//添加服务注册信息

        serviceMetadata.getAttachments().putAll(map);//将配置保存如服务元信息中

        ref = createProxy(map);//创建代理

        serviceMetadata.setTarget(ref);
        serviceMetadata.addAttribute(PROXY_CLASS_REF, ref);
        // 根据服务名，ReferenceConfig，代理类构建 ConsumerModel，
        // 并将 ConsumerModel 存入到 ApplicationModel 中
        ConsumerModel consumerModel = repository.lookupReferredService(serviceMetadata.getServiceKey());
        consumerModel.setProxyObject(ref);
        consumerModel.init(attributes);

        initialized = true;

        checkInvokerAvailable();//检查引入的服务是否可用

        dispatch(new ReferenceConfigInitializedEvent(this, invoker));//发送引入初始化完成事件
 ｝
```
在服务对象创建后会进行很多对象属性配置的合法性检查，我们看一下`checkAndUpdateSubConfigs`中都做了哪些配置的检查

```java
public void checkAndUpdateSubConfigs() {
        if (StringUtils.isEmpty(interfaceName)) {
            throw new IllegalStateException("<dubbo:reference interface=\"\" /> interface not allow null!");
        }
        completeCompoundConfigs(consumer);//完成服务接口上 Application、Module、Registries和Monitor的设置
        if (consumer != null) {
            if (StringUtils.isEmpty(registryIds)) {
                setRegistryIds(consumer.getRegistryIds());
            }
        }
        // 获取 consumer 的全局默认配置
        checkDefault();
        this.refresh(); //检查配置方法方法参数
        if (getGeneric() == null && getConsumer() != null) {
            setGeneric(getConsumer().getGeneric());
        }
        //泛型配置
        if (ProtocolUtils.isGeneric(generic)) {
            interfaceClass = GenericService.class;
        } else {
            try {
                interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            checkInterfaceAndMethods(interfaceClass, getMethods());
        }
        //初始化元务原数据
        serviceMetadata.setVersion(getVersion());
        serviceMetadata.setGroup(getGroup());
        serviceMetadata.setDefaultGroup(getGroup());
        serviceMetadata.setServiceType(getActualInterface());
        serviceMetadata.setServiceInterfaceName(interfaceName);
        // 创建服务URL对象
        serviceMetadata.setServiceKey(URL.buildKey(interfaceName, group, version));
        //配置中心服务订阅
        ServiceRepository repository = ApplicationModel.getServiceRepository();
        ServiceDescriptor serviceDescriptor = repository.registerService(interfaceClass);
        repository.registerConsumer(
                serviceMetadata.getServiceKey(),
                serviceDescriptor,
                this,
                null,
                serviceMetadata);
        resolveFile();//dubbo.resolve.file文件中获取服务url配置信息 
        ConfigValidationUtils.validateReferenceConfig(this);//再次验证检查配置
        postProcessConfig();//postProcessConfig配置调用
    }

```
再消费者服务配置信息检查并通过默认设置完整后，接下来就是创建代理对象了，即`ref = createProxy(map);`
```java
private T createProxy(Map<String, String> map) {
        if (shouldJvmRefer(map)) {//jvm本地引入
            //使用本地地址构建url信息
            URL url = new URL(LOCAL_PROTOCOL, LOCALHOST_VALUE, 0, interfaceClass.getName()).addParameters(map);
            invoker = REF_PROTOCOL.refer(interfaceClass, url);//本地引用invoker生成
            if (logger.isInfoEnabled()) {
                logger.info("Using injvm service " + interfaceClass.getName());
            }
        } else {
            urls.clear();
            if (url != null && url.length() > 0) { // 用户配置url信息,表明用户可能想进行点对点调用
                 // 当需要配置多个 url 时，可用分号进行分割，这里会进行切分
                String[] us = SEMICOLON_SPLIT_PATTERN.split(url);
                if (us != null && us.length > 0) {
                    for (String u : us) {
                        URL url = URL.valueOf(u);
                        if (StringUtils.isEmpty(url.getPath())) {
                            url = url.setPath(interfaceName); // 设置接口全限定名为 url 路径
                        }
                        // 检测 url 协议是否为 registry，若是，表明用户想使用指定的注册中心
                        if (UrlUtils.isRegistry(url)) {
                             // 将 map 转换为查询字符串，并作为 refer 参数的值添加到 url 中
                            urls.add(url.addParameterAndEncoded(REFER_KEY, StringUtils.toQueryString(map)));
                        } else {
                            // 合并 url，移除服务提供者的一些配置（这些配置来源于用户配置的 url 属性），
                            // 比如线程池相关配置。并保留服务提供者的部分配置，比如版本，group，时间戳等
                            // 最后将合并后的配置设置为 url 查询字符串中。
                            urls.add(ClusterUtils.mergeUrl(url, map));
                        }
                    }
                }
            } else { // 从注册中心的配置中组装url信息
                // 如果协议不是在jvm本地中
                if (!LOCAL_PROTOCOL.equalsIgnoreCase(getProtocol())) {
                    checkRegistry();//检查注册中心是否存在(如果当前配置不存在则获取服务默认配置),然后将他们转换到RegistryConfig中
                    //通过注册中心配置信息组装URL
                    List<URL> us = ConfigValidationUtils.loadRegistries(this, false);
                    if (CollectionUtils.isNotEmpty(us)) {
                        for (URL u : us) {
                            //添加monitor监控信息
                            URL monitorUrl = ConfigValidationUtils.loadMonitor(this, u);
                            if (monitorUrl != null) {
                                map.put(MONITOR_KEY, URL.encode(monitorUrl.toFullString()));
                            }
                            urls.add(u.addParameterAndEncoded(REFER_KEY, StringUtils.toQueryString(map)));
                        }
                    }
                    if (urls.isEmpty()) {
                        throw new IllegalStateException("No such any registry to reference " + interfaceName + " on the consumer " + NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion() + ", please config <dubbo:registry address=\"...\" /> to your spring config.");
                    }
                }
            }
            if (urls.size() == 1) {//单个注册中心或服务提供者(服务直连，下同)
                 // 调用 RegistryProtocol 的 refer 构建 Invoker 实例
                invoker = REF_PROTOCOL.refer(interfaceClass, urls.get(0));
            } else {//多个注册中心或多个服务提供者，或者两者混合
                List<Invoker<?>> invokers = new ArrayList<Invoker<?>>();
                URL registryURL = null;
                // 获取所有的 Invoker
                for (URL url : urls) {
                    invokers.add(REF_PROTOCOL.refer(interfaceClass, url));
                    if (UrlUtils.isRegistry(url)) {
                        registryURL = url; // 保存使用注册中心的最新的URL信息
                    }
                }
                if (registryURL != null) { // 注册中心URL存在
                    // 对于对区域订阅方案，默认使用"zone-aware"区域
                    String cluster = registryURL.getParameter(CLUSTER_KEY, ZoneAwareCluster.NAME);
                    // invoker 包装顺序: ZoneAwareClusterInvoker(StaticDirectory) -> FailoverClusterInvoker(RegistryDirectory, routing happens here) -> Invoker
                    invoker = Cluster.getCluster(cluster, false).join(new StaticDirectory(registryURL, invokers));
                } else { // 如果不存在注册中心连接，只能使用直连
                    //如果订阅区域未设置，则设置为默认区域"zone-aware"
                    String cluster = CollectionUtils.isNotEmpty(invokers)
                            ? (invokers.get(0).getUrl() != null ? invokers.get(0).getUrl().getParameter(CLUSTER_KEY, ZoneAwareCluster.NAME) : Cluster.DEFAULT)
                            : Cluster.DEFAULT;
                    // 创建 StaticDirectory 实例，并由 Cluster 对多个 Invoker 进行合并
                    invoker = Cluster.getCluster(cluster).join(new StaticDirectory(invokers));
                }
            }
        }
        if (logger.isInfoEnabled()) {
            logger.info("Refer dubbo service " + interfaceClass.getName() + " from url " + invoker.getUrl());
        }
        // 创建服务代理
        return (T) PROXY_FACTORY.getProxy(invoker, ProtocolUtils.isGeneric(generic));
    }
```
Invoker 是 Dubbo 的核心模型，代表一个可执行体。在服务提供方，Invoker 用于调用服务提供类。在服务消费方，Invoker 用于执行远程调用。Invoker 是由 Protocol 实现类构建而来。Protocol 实现类有很多，我们看看一下最常用的两个，分别是 RegistryProtocol 和 DubboProtocol是如何创建invoker，即`REF_PROTOCOL.refer(interfaceClass, url)`方法如何创建Invoker的。
```java
//RegistryProtocol
public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        url = getRegistryUrl(url);//将 registry 参数值，并将其设置为协议头并移除registry 参数值
        Registry registry = registryFactory.getRegistry(url);//获取注册中心实例
        if (RegistryService.class.equals(type)) {
            return proxyFactory.getInvoker((T) registry, type, url);//如果当前类型是RegistryService,则通过代理工厂获取invoker
        }

        // 将 url 查询字符串转为 Map 并获取group信息:group="a,b" or group="*"
        Map<String, String> qs = StringUtils.parseQueryString(url.getParameterAndDecoded(REFER_KEY));
        String group = qs.get(GROUP_KEY);
        if (group != null && group.length() > 0) {
            if ((COMMA_SPLIT_PATTERN.split(group)).length > 1 || "*".equals(group)) {
                // 通过 SPI 加载 MergeableCluster 实例，并调用 doRefer 继续执行服务引用逻辑
                return doRefer(Cluster.getCluster(MergeableCluster.NAME), registry, type, url);
            }
        }

        Cluster cluster = Cluster.getCluster(qs.get(CLUSTER_KEY));
        return doRefer(cluster, registry, type, url);// 调用 doRefer 继续执行服务引用逻辑
 ｝

private <T> Invoker<T> doRefer(Cluster cluster, Registry registry, Class<T> type, URL url) {
        RegistryDirectory<T> directory = new RegistryDirectory<T>(type, url);// 创建 RegistryDirectory 实例
         // 设置注册中心和协议
        directory.setRegistry(registry);
        directory.setProtocol(protocol);
        // 获取REFER_KEY的所有设置信息
        Map<String, String> parameters = new HashMap<String, String>(directory.getConsumerUrl().getParameters());
        // 生成服务消费者连接
        URL subscribeUrl = new URL(CONSUMER_PROTOCOL, parameters.remove(REGISTER_IP_KEY), 0, type.getName(), parameters);
        if (directory.isShouldRegister()) {
            directory.setRegisteredConsumerUrl(subscribeUrl);
            registry.register(directory.getRegisteredConsumerUrl());
        }
        //更新路由调用链路
        directory.buildRouterChain(subscribeUrl);
         // 订阅 providers、configurators、routers 等节点数据
        directory.subscribe(toSubscribeUrl(subscribeUrl));
         // 一个注册中心可能有多个服务提供者，因此这里需要将多个服务提供者合并为一个
        Invoker<T> invoker = cluster.join(directory);
        List<RegistryProtocolListener> listeners = findRegistryProtocolListeners(url);
        if (CollectionUtils.isEmpty(listeners)) {
            return invoker;
        }
        //引入了RegistryProtocol侦听器，以使用户有机会自定义或更改导出并引用RegistryProtocol的行为。
        // 例如：在满足某些条件时立即重新导出或重新引用。
        RegistryInvokerWrapper<T> registryInvokerWrapper = new RegistryInvokerWrapper<>(directory, cluster, invoker);
        for (RegistryProtocolListener listener : listeners) {
            listener.onRefer(this, registryInvokerWrapper);
        }
        return registryInvokerWrapper;
    }
```
以上就是RegistryProtocol的服务引用，接下来我们看看DubboProtocol的服务引入,DubboProtocol继承于AbstractProtocol，所以他的入口在AbstractProtocol中
```java
//AbstractProtocol
public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        return new AsyncToSyncInvoker<>(protocolBindingRefer(type, url));
    }
    
//DubboProtocol
public <T> Invoker<T> protocolBindingRefer(Class<T> serviceType, URL url) throws RpcException {
        optimizeSerialization(url);
        // 创建 rpc invoker.
        DubboInvoker<T> invoker = new DubboInvoker<T>(serviceType, url, getClients(url), invokers);
        invokers.add(invoker);
        return invoker;
    }
    
//通过url获取client
private ExchangeClient[] getClients(URL url) {
    // 是否共享连接
    boolean useShareConnect = false;
    int connections = url.getParameter(CONNECTIONS_KEY, 0);
    List<ReferenceCountExchangeClient> shareClients = null;
    // 如果未配置 connections，则共享连接, 否则, 一个服务使用一个连接
    if (connections == 0) {
        useShareConnect = true;
        //xml配置高于其他方式的配置，获取共享连接数
        String shareConnectionsStr = url.getParameter(SHARE_CONNECTIONS_KEY, (String) null);
        connections = Integer.parseInt(StringUtils.isBlank(shareConnectionsStr) ? ConfigUtils.getProperty(SHARE_CONNECTIONS_KEY,
                DEFAULT_SHARE_CONNECTIONS) : shareConnectionsStr);
        shareClients = getSharedClient(url, connections);//获取共享客户端
    }
    ExchangeClient[] clients = new ExchangeClient[connections];
    for (int i = 0; i < clients.length; i++) {
        if (useShareConnect) {
            clients[i] = shareClients.get(i);//使用共享的客户端
        } else {
            clients[i] = initClient(url); // 初始化新的客户端
        }
    }
    return clients;
}
//获取共享客户端
private List<ReferenceCountExchangeClient> getSharedClient(URL url, int connectNum) {
        String key = url.getAddress();
        List<ReferenceCountExchangeClient> clients = referenceClientMap.get(key);//从缓存中获取
        if (checkClientCanUse(clients)) {//如果缓存的客户端还能使用
            batchClientRefIncr(clients);//增加引用计数
            return clients;
        }
        //如果从缓存中没有获取到可用的客户端，则初始化客户端
        locks.putIfAbsent(key, new Object());
        synchronized (locks.get(key)) {
            clients = referenceClientMap.get(key);
            if (checkClientCanUse(clients)) {
                batchClientRefIncr(clients);
                return clients;
            }
            // 连接是必须大于等于1
            connectNum = Math.max(connectNum, 1);
            // 如果共享客户端为空，则第一次初始化
            if (CollectionUtils.isEmpty(clients)) {
                clients = buildReferenceCountExchangeClientList(url, connectNum);
                referenceClientMap.put(key, clients);//放入共享客户端缓存
            } else {
                for (int i = 0; i < clients.size(); i++) {
                    ReferenceCountExchangeClient referenceCountExchangeClient = clients.get(i);
                    // 如果列表中有一个不再可用的客户端，请创建一个新客户端以替换该客户端。
                    if (referenceCountExchangeClient == null || referenceCountExchangeClient.isClosed()) {
                        clients.set(i, buildReferenceCountExchangeClient(url));
                        continue;
                    }
                    referenceCountExchangeClient.incrementAndGetCount();//增加引用计数
                }
            }
            locks.remove(key);
            return clients;
        }
    }
//创建单个引用客户端
private ReferenceCountExchangeClient buildReferenceCountExchangeClient(URL url) {
   ExchangeClient exchangeClient = initClient(url);//创建客户端
   return new ReferenceCountExchangeClient(exchangeClient);
}
private ExchangeClient initClient(URL url) {
    // 设置client类型
    String str = url.getParameter(CLIENT_KEY, url.getParameter(SERVER_KEY, DEFAULT_REMOTING_CLIENT));
    //添加编码格式
    url = url.addParameter(CODEC_KEY, DubboCodec.NAME);
    // 开启心跳检测
    url = url.addParameterIfAbsent(HEARTBEAT_KEY, String.valueOf(DEFAULT_HEARTBEAT));

    // 检测客户端类型是否存在，不存在则抛出异常
    if (str != null && str.length() > 0 && !ExtensionLoader.getExtensionLoader(Transporter.class).hasExtension(str)) {
        throw new RpcException("Unsupported client type: " + str + "," +
                " supported client type is " + StringUtils.join(ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions(), " "));
    }
    ExchangeClient client;
    try {
        // 是否是懒加载
        if (url.getParameter(LAZY_CONNECT_KEY, false)) {
            client = new LazyConnectExchangeClient(url, requestHandler); // 创建懒加载 ExchangeClient 实例

        } else {
            client = Exchangers.connect(url, requestHandler); // 创建普通 ExchangeClient 实例
        }

    } catch (RemotingException e) {
        throw new RpcException("Fail to create remoting client for service(" + url + "): " + e.getMessage(), e);
    }

    return client;
}    

//Exchangers.java
public static ExchangeClient connect(URL url, ExchangeHandler handler) throws RemotingException {
     if (url == null) {
         throw new IllegalArgumentException("url == null");
     }
     if (handler == null) {
         throw new IllegalArgumentException("handler == null");
     }
     url = url.addParameterIfAbsent(Constants.CODEC_KEY, "exchange");
     return getExchanger(url).connect(url, handler);
 }
 
public static Exchanger getExchanger(URL url) {
     String type = url.getParameter(Constants.EXCHANGER_KEY, Constants.DEFAULT_EXCHANGER);
     return getExchanger(type);
 }    

public static Exchanger getExchanger(String type) {
    return ExtensionLoader.getExtensionLoader(Exchanger.class).getExtension(type);
}
     
HeaderExchanger.java
public ExchangeClient connect(URL url, ExchangeHandler handler) throws RemotingException {
    return new HeaderExchangeClient(Transporters.connect(url, new DecodeHandler(new HeaderExchangeHandler(handler))), true);
}
    
public static Client connect(URL url, ChannelHandler... handlers) throws RemotingException {
    if (url == null) {
        throw new IllegalArgumentException("url == null");
    }
    ChannelHandler handler;
    if (handlers == null || handlers.length == 0) {
        handler = new ChannelHandlerAdapter();
    } else if (handlers.length == 1) {
        handler = handlers[0];
    } else {
        handler = new ChannelHandlerDispatcher(handlers);// 如果 handler 数量大于1，则创建一个 ChannelHandler 分发器
    }
     // 获取 Transporter 自适应拓展类(如NettyTransporter)，并调用 connect 方法生成 Client 实例
    return getTransporter().connect(url, handler);
}   
    
  //NettyTransporter.java
public Client connect(URL url, ChannelHandler handler) throws RemotingException {
     return new NettyClient(url, handler); //创建netty客户端
}
```
Invoker 创建完毕后，接下来要做的事情是为服务接口生成代理对象。有了代理对象，即可进行远程调用。代理对象生成的入口方法为 ProxyFactory 的 getProxy，接下来进行分析。
```java
//AbstractProxyFactory
public <T> T getProxy(Invoker<T> invoker, boolean generic) throws RpcException {
    Set<Class<?>> interfaces = new HashSet<>();
    String config = invoker.getUrl().getParameter(INTERFACES);
    if (config != null && config.length() > 0) {
        String[] types = COMMA_SPLIT_PATTERN.split(config);
        for (String type : types) {
            interfaces.add(ReflectUtils.forName(type));
        }
    }
    //泛型服务
    if (generic) {
        if (!GenericService.class.isAssignableFrom(invoker.getInterface())) {
            interfaces.add(com.alibaba.dubbo.rpc.service.GenericService.class);
        }
        try {
            // 通过url找寻真正的inteface
            String realInterface = invoker.getUrl().getParameter(Constants.INTERFACE);
            interfaces.add(ReflectUtils.forName(realInterface));
        } catch (Throwable e) {
        }
    }

    interfaces.add(invoker.getInterface());
    interfaces.addAll(Arrays.asList(INTERNAL_INTERFACES));
    //调用重载方法
    return getProxy(invoker, interfaces.toArray(new Class<?>[0]));
}
    
 //JavassistProxyFactory
 public <T> T getProxy(Invoker<T> invoker, Class<?>[] interfaces) {
    //使用Dubbo自己的Proxy获取代理类，感兴趣的可以自己了解下
    return (T) Proxy.getProxy(interfaces).newInstance(new InvokerInvocationHandler(invoker));
}
```
到这里整个引用服务就分析完了，这样我们引用服务就拿到代理的Invoker，可以进行服务的调用了，让调用者像调用本地服务一样调用远程服务。
