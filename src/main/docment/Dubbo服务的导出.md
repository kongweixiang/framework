# Dubbo服务的导出
  Dubbo 服务导出过程始于 Spring 容器发布刷新事件，Dubbo 在接收到事件后，会立即执行服务导出逻辑。整个逻辑大致可分为三个部分，第一部分是前置工作，主要用于检查参数，组装 URL。第二部分是导出服务，包含导出服务到本地 (JVM)，和导出服务到远程两个过程。第三部分是向注册中心注册服务，用于服务发现。  
  下边是Dubbo提供的一张服务导出的时序图，从图中我们可以大概的了解到Dubbo导出过程：
  
  
  下边我们大概了解一下源码的导出过程：当DubboBootstrap启动后进行服务的进行导出发起的
```java
//DubboBootstrap.java
    public DubboBootstrap start() {
        if (started.compareAndSet(false, true)) {
            ready.set(false);
            initialize();
            if (logger.isInfoEnabled()) {
                logger.info(NAME + " is starting...");
            }
            // 1. export Dubbo Services
            exportServices(); //调用ServiceConfig.export()导出服务
               ···
}
```
下边我们看服务Invoker的导出过程
```java
public synchronized void export() {
        if (!shouldExport()) {
            return;
        }

        if (bootstrap == null) {
            bootstrap = DubboBootstrap.getInstance();
            bootstrap.initialize();
        }

        checkAndUpdateSubConfigs();

        //init serviceMetadata
        serviceMetadata.setVersion(getVersion());
        serviceMetadata.setGroup(getGroup());
        serviceMetadata.setDefaultGroup(getGroup());
        serviceMetadata.setServiceType(getInterfaceClass());
        serviceMetadata.setServiceInterfaceName(getInterface());
        serviceMetadata.setTarget(getRef());

        if (shouldDelay()) { //如果延迟的话使用任务延迟导出
            DELAY_EXPORT_EXECUTOR.schedule(this::doExport, getDelay(), TimeUnit.MILLISECONDS);
        } else {
            doExport(); //发起导出
        }

        exported(); //发送ServiceConfigExportedEvent事件
    }

protected synchronized void doExport() {
        if (unexported) {
            throw new IllegalStateException("The service " + interfaceClass.getName() + " has already unexported!");
        }
        if (exported) {
            return;
        }
        exported = true;

        if (StringUtils.isEmpty(path)) {
            path = interfaceName;
        }
        doExportUrls(); //导出操作
    }

 private void doExportUrls() {
        //获取注册中心信息
        ServiceRepository repository = ApplicationModel.getServiceRepository();
        ServiceDescriptor serviceDescriptor = repository.registerService(getInterfaceClass());
        repository.registerProvider(
                getUniqueServiceName(),
                ref,
                serviceDescriptor,
                this,
                serviceMetadata
        );

        List<URL> registryURLs = ConfigValidationUtils.loadRegistries(this, true);

        for (ProtocolConfig protocolConfig : protocols) { //多协议导出
            String pathKey = URL.buildKey(getContextPath(protocolConfig)
                    .map(p -> p + "/" + path)
                    .orElse(path), group, version);
            // In case user specified path, register service one more time to map it to path.
            repository.registerService(pathKey, interfaceClass);
            // TODO, uncomment this line once service key is unified
            serviceMetadata.setServiceKey(pathKey);
            doExportUrlsFor1Protocol(protocolConfig, registryURLs); //根据协议导出到注册中心
        }
    }

private void doExportUrlsFor1Protocol(ProtocolConfig protocolConfig, List<URL> registryURLs) {
        String name = protocolConfig.getName();
        if (StringUtils.isEmpty(name)) {
            name = DUBBO;
        }

        Map<String, String> map = new HashMap<String, String>(); //构建参数映射集合
       //将所有的服务信息放入到map中，包含
           ……
        //init serviceMetadata attachments
        serviceMetadata.getAttachments().putAll(map);

        // export service
        String host = findConfigedHosts(protocolConfig, registryURLs, map);
        Integer port = findConfigedPorts(protocolConfig, name, map);
        //Dubbo中URL 是 Dubbo 配置的载体，通过 URL 可让 Dubbo 的各种配置在各个模块之间传递。
        //组装 URL感兴趣的同学可以自行查查看源码
        URL url = new URL(name, host, port, getContextPath(protocolConfig).map(p -> p + "/" + path).orElse(path), map);
        
            ……
        Invoker<?> invoker = PROXY_FACTORY.getInvoker(ref, (Class) interfaceClass, url);
        DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);
        Exporter<?> exporter = PROTOCOL.export(wrapperInvoker);
        //这里的导出分为本地和远程导出
           /**
            本地导出为InjvmProtocol.export()比较简单
            远程导出为RegistryProtocol.export()。分为向注册中心注册服务，向注册中心进行订阅 override 数据，创建并返回 DestroyableExporter三大部分
            */
        exporters.add(exporter);

        this.urls.add(url);
    }
```
Invoker 是 Dubbo 的核心模型，Dubbo的所有服务都是向它靠拢的，Dubbo服务在提供和引用时都是导出或导入为Invoker对象，它代表一个可执行体，可向它发起 invoke 调用，它有可能是一个本地的实现，也可能是一个远程的实现，也可能一个集群实现。
Invoker 是由 ProxyFactory 创建而来，在Dubbo 中默认的 ProxyFactory 实现类是 JavassistProxyFactory。
```java
//JavassistProxyFactory.class
    public <T> Invoker<T> getInvoker(T proxy, Class<T> type, URL url) {
        final Wrapper wrapper = Wrapper.getWrapper(proxy.getClass().getName().indexOf('$') < 0 ? proxy.getClass() : type);//包装服务
        return new AbstractProxyInvoker<T>(proxy, type, url) {
            @Override
            protected Object doInvoke(T proxy, String methodName,
                                      Class<?>[] parameterTypes,
                                      Object[] arguments) throws Throwable {
                return wrapper.invokeMethod(proxy, methodName, parameterTypes, arguments);
            }
        };
    }

//Wrapper 
public static Wrapper getWrapper(Class<?> c) {
        while (ClassGenerator.isDynamicClass(c)) // can not wrapper on dynamic class.
        {
            c = c.getSuperclass();
        }

        if (c == Object.class) {
            return OBJECT_WRAPPER;
        }

        return WRAPPER_MAP.computeIfAbsent(c, key -> makeWrapper(key)); 
       //makeWrapper进行包装，这里主要是进行方法的调用代理，参数的检验，异常等的处理等操作。
    }
```

下边我们看一下DubboProtocol的export()
```java
public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        URL url = invoker.getUrl();

        //获取服务标识: 由服务组名，服务名，服务版本号以及端口组成。
        String key = serviceKey(url);
        // 创建 DubboExporter
        DubboExporter<T> exporter = new DubboExporter<T>(invoker, key, exporterMap); 
        exporterMap.put(key, exporter);

        //本地存根相关代码
        Boolean isStubSupportEvent = url.getParameter(STUB_EVENT_KEY, DEFAULT_STUB_EVENT);
        Boolean isCallbackservice = url.getParameter(IS_CALLBACK_SERVICE, false);
        if (isStubSupportEvent && !isCallbackservice) {
            String stubServiceMethods = url.getParameter(STUB_EVENT_METHODS_KEY);
            if (stubServiceMethods == null || stubServiceMethods.length() == 0) {
                if (logger.isWarnEnabled()) {
                    logger.warn(new IllegalStateException("consumer [" + url.getParameter(INTERFACE_KEY) +
                            "], has set stubproxy support event ,but no stub methods founded."));
                }

            }
        }
        // 启动服务器
        openServer(url);
        // 优化序列化
        optimizeSerialization(url);

        return exporter;
    }

    private void openServer(URL url) {
            // 获取 host:port，并将其作为服务器实例的 key，用于标识当前的服务器实例
            String key = url.getAddress();
            //客户端可以导出仅用于服务器调用的服务
            boolean isServer = url.getParameter(IS_SERVER_KEY, true);
            if (isServer) {
                ProtocolServer server = serverMap.get(key);
                if (server == null) {
                    synchronized (this) {
                        server = serverMap.get(key);
                        if (server == null) {
                            serverMap.put(key, createServer(url));
                        }
                    }
                } else {
                    // 在同一台机器上（单网卡），同一个端口上仅允许启动一个服务器实例。若某个端口上已有服务器实例，此时则调用 reset 方法重置服务器的一些配置。
                    server.reset(url);
                }
            }
        }

    //创建服务
    private ProtocolServer createServer(URL url) {
        url = URLBuilder.from(url)
                // send readonly event when server closes, it's enabled by default
                .addParameterIfAbsent(CHANNEL_READONLYEVENT_SENT_KEY, Boolean.TRUE.toString())
                .addParameterIfAbsent(HEARTBEAT_KEY, String.valueOf(DEFAULT_HEARTBEAT)) // 添加心跳检测配置到 url 中
                .addParameter(CODEC_KEY, DubboCodec.NAME)//添加编码解码器参数
                .build();
        String str = url.getParameter(SERVER_KEY, DEFAULT_REMOTING_SERVER);// 获取 server 参数，默认为 netty

        if (str != null && str.length() > 0 && !ExtensionLoader.getExtensionLoader(Transporter.class).hasExtension(str)) {
            throw new RpcException("Unsupported server type: " + str + ", url: " + url);
        }

        ExchangeServer server;
        try {
            server = Exchangers.bind(url, requestHandler); // 创建 ExchangeServer
        } catch (RemotingException e) {
            throw new RpcException("Fail to start server(url: " + url + ") " + e.getMessage(), e);
        }

        str = url.getParameter(CLIENT_KEY);// 获取 client 参数，可指定 netty，mina
        if (str != null && str.length() > 0) {
            Set<String> supportedTypes = ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions();// 获取所有的 Transporter 实现类名称集合，比如 supportedTypes = [netty, mina]
            // 检测当前 Dubbo 所支持的 Transporter 实现类名称列表中，
            // 是否包含 client 所表示的 Transporter，若不包含，则抛出异常            
            if (!supportedTypes.contains(str)) {
                throw new RpcException("Unsupported client type: " + str);
            }
        }

        return new DubboProtocolServer(server);
    }

    public static ExchangeServer bind(URL url, ExchangeHandler handler) throws RemotingException {
        if (url == null) {
            throw new IllegalArgumentException("url == null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler == null");
        }
        url = url.addParameterIfAbsent(Constants.CODEC_KEY, "exchange");
        // 获取 Exchanger，默认为 HeaderExchanger。
        // 紧接着调用 HeaderExchanger 的 bind 方法创建 ExchangeServer 实例
        return getExchanger(url).bind(url, handler);
    }
        //HeaderExchanger.bind(url, handler)
        public ExchangeServer bind(URL url, ExchangeHandler handler) throws RemotingException {
            return new HeaderExchangeServer(Transporters.bind(url, new DecodeHandler(new HeaderExchangeHandler(handler))));
        }

     //Transporters.bind
    public RemotingServer bind(URL url, ChannelHandler handler) throws RemotingException {
            return new NettyServer(url, handler);
        }
    //NettyServer(url, handler)调用
    public AbstractServer(URL url, ChannelHandler handler) throws RemotingException {
        super(url, handler);
        localAddress = getUrl().toInetSocketAddress();
        // 获取 ip 和端口
        String bindIp = getUrl().getParameter(Constants.BIND_IP_KEY, getUrl().getHost());
        int bindPort = getUrl().getParameter(Constants.BIND_PORT_KEY, getUrl().getPort());
        if (url.getParameter(ANYHOST_KEY, false) || NetUtils.isInvalidLocalHost(bindIp)) {
            bindIp = ANYHOST_VALUE;
        }
        bindAddress = new InetSocketAddress(bindIp, bindPort);
        this.accepts = url.getParameter(ACCEPTS_KEY, DEFAULT_ACCEPTS);// 获取最大可接受连接数
        this.idleTimeout = url.getParameter(IDLE_TIMEOUT_KEY, DEFAULT_IDLE_TIMEOUT); //超时
        try {
            doOpen();// 调用模板方法 doOpen 启动服务器
            if (logger.isInfoEnabled()) {
                logger.info("Start " + getClass().getSimpleName() + " bind " + getBindAddress() + ", export " + getLocalAddress());
            }
        } catch (Throwable t) {
            throw new RemotingException(url.toInetSocketAddress(), null, "Failed to bind " + getClass().getSimpleName()
                    + " on " + getLocalAddress() + ", cause: " + t.getMessage(), t);
        }
        executor = executorRepository.createExecutorIfAbsent(url);
    }
```
我们重点关注 doOpen 抽象方法，该方法需要子类实现。
```java
protected void doOpen() throws Throwable {
        NettyHelper.setNettyLoggerFactory();
        //创建 boss 和 worker 线程池
        ExecutorService boss = Executors.newCachedThreadPool(new NamedThreadFactory("NettyServerBoss", true));
        ExecutorService worker = Executors.newCachedThreadPool(new NamedThreadFactory("NettyServerWorker", true));
        ChannelFactory channelFactory = new NioServerSocketChannelFactory(boss, worker, getUrl().getPositiveParameter(IO_THREADS_KEY, Constants.DEFAULT_IO_THREADS));
        // 创建 ServerBootstrap
        bootstrap = new ServerBootstrap(channelFactory);

        final NettyHandler nettyHandler = new NettyHandler(getUrl(), this);
        channels = nettyHandler.getChannels();
        bootstrap.setOption("child.tcpNoDelay", true);
        bootstrap.setOption("backlog", getUrl().getPositiveParameter(BACKLOG_KEY, Constants.DEFAULT_BACKLOG));
        bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            @Override
            public ChannelPipeline getPipeline() {
                NettyCodecAdapter adapter = new NettyCodecAdapter(getCodec(), getUrl(), NettyServer.this);
                ChannelPipeline pipeline = Channels.pipeline();
                /*int idleTimeout = getIdleTimeout();
                if (idleTimeout > 10000) {
                    pipeline.addLast("timer", new IdleStateHandler(timer, idleTimeout / 1000, 0, 0));
                }*/
                pipeline.addLast("decoder", adapter.getDecoder());
                pipeline.addLast("encoder", adapter.getEncoder());
                pipeline.addLast("handler", nettyHandler);
                return pipeline;
            }
        });
        // bind 地址
        channel = bootstrap.bind(getBindAddress());
    }
```
以上就是 NettyServer 创建的过程，dubbo 默认使用的 NettyServer 是基于 netty 3.x 版本实现的，比较老了。因此 Dubbo 另外提供了 netty 4.x 版本的 NettyServer，大家可在使用 Dubbo 的过程中按需进行配置。

**服务注册**  
在服务导出过程中我们会进行服务注册，服务注册操作对于 Dubbo 来说不是必需的，通过服务直连的方式就可以绕过注册中心。对于 Dubbo 来说，注册中心虽不是必需，但却是必要的。  
Dubbo提供了对多中服务注册中心，我们比较熟悉的应该是Zookeeper的注册中心了，我们看一下注册过程：
在RegistryProtocol中发起
```java
public <T> Exporter<T> export(final Invoker<T> originInvoker) throws RpcException {
        URL registryUrl = getRegistryUrl(originInvoker);
        // url to export locally
        URL providerUrl = getProviderUrl(originInvoker);

        final URL overrideSubscribeUrl = getSubscribedOverrideUrl(providerUrl);
        final OverrideListener overrideSubscribeListener = new OverrideListener(overrideSubscribeUrl, originInvoker);
        overrideListeners.put(overrideSubscribeUrl, overrideSubscribeListener);

        providerUrl = overrideUrlWithConfig(providerUrl, overrideSubscribeListener);
        //export invoker
        final ExporterChangeableWrapper<T> exporter = doLocalExport(originInvoker, providerUrl);

        // url to registry
        final Registry registry = getRegistry(originInvoker);
        final URL registeredProviderUrl = getUrlToRegistry(providerUrl, registryUrl);

        // decide if we need to delay publish
        boolean register = providerUrl.getParameter(REGISTER_KEY, true);
        if (register) {
            register(registryUrl, registeredProviderUrl); //服务注册
        }

        // register stated url on provider model
        registerStatedUrl(registryUrl, registeredProviderUrl, register);


        exporter.setRegisterUrl(registeredProviderUrl);
        exporter.setSubscribeUrl(overrideSubscribeUrl);

        // Deprecated! Subscribe to override rules in 2.6.x or before.
        registry.subscribe(overrideSubscribeUrl, overrideSubscribeListener);

        notifyExport(exporter);
        //Ensure that a new exporter instance is returned every time export
        return new DestroyableExporter<>(exporter);
    }

    private void register(URL registryUrl, URL registeredProviderUrl) {
            Registry registry = registryFactory.getRegistry(registryUrl); //1. 获取注册中心
            registry.register(registeredProviderUrl); //2. 注册服务
    }
```
  1. 获取注册中心：调用（AbstractRegistryFactory.class）
```java
 @Override
    public Registry getRegistry(URL url) {
        if (destroyed.get()) {
            LOGGER.warn("All registry instances have been destroyed, failed to fetch any instance. " +
                    "Usually, this means no need to try to do unnecessary redundant resource clearance, all registries has been taken care of.");
            return DEFAULT_NOP_REGISTRY;
        }

        url = URLBuilder.from(url)
                .setPath(RegistryService.class.getName())
                .addParameter(INTERFACE_KEY, RegistryService.class.getName())
                .removeParameters(EXPORT_KEY, REFER_KEY)
                .build();
        String key = createRegistryCacheKey(url);
        LOCK.lock();
        try {
            Registry registry = REGISTRIES.get(key);
            if (registry != null) {
                return registry;
            }
            registry = createRegistry(url); //创建注册中心
            if (registry == null) {
                throw new IllegalStateException("Can not create registry " + url);
            }
            REGISTRIES.put(key, registry);
            return registry;
        } finally {
            // Release the lock
            LOCK.unlock();
        }
    }

    //创建注册中心 例如：ZookeeperRegistryFactory
    public Registry createRegistry(URL url) {
        return new ZookeeperRegistry(url, zookeeperTransporter);
    }
    public ZookeeperRegistry(URL url, ZookeeperTransporter zookeeperTransporter) {
            super(url);
            if (url.isAnyHost()) {
                throw new IllegalStateException("registry address == null");
            }
            String group = url.getParameter(GROUP_KEY, DEFAULT_ROOT);
            if (!group.startsWith(PATH_SEPARATOR)) {
                group = PATH_SEPARATOR + group;
            }
            this.root = group;
           // 创建 Zookeeper 客户端，默认为 CuratorZookeeperTransporter
            zkClient = zookeeperTransporter.connect(url);  //连接客户端并启动
            zkClient.addStateListener((state) -> { // 添加状态监听器
                if (state == StateListener.RECONNECTED) {
                    ZookeeperRegistry.this.fetchLatestAddresses();
                } else if (state == StateListener.NEW_SESSION_CREATED) {
                    try {
                        ZookeeperRegistry.this.recover();
                    } catch (Exception e) {
                        logger.error(e.getMessage(), e);
                    }
                } else if (state == StateListener.SESSION_LOST) {
                    logger.warn("Url of this instance will be deleted from registry soon. " +
                            "Dubbo client will try to re-register once a new session is created.");
                } else if (state == StateListener.SUSPENDED) {
    
                } else if (state == StateListener.CONNECTED) {
    
                }
            });
        }
```
目前我们已经获取了注册中心并初始化了客户端连接，来看第二步registry.register(registeredProviderUrl); 注册服务。
我们以FailbackRegistry为例
```java
public void register(URL url) {
        if (!acceptable(url)) {
            logger.info("URL " + url + " will not be registered to Registry. Registry " + url + " does not accept service of this protocol type.");
            return;
        }
        super.register(url);
        removeFailedRegistered(url);
        removeFailedUnregistered(url);
        try {
            // 模板方法调用doRegister注册
            doRegister(url);
        } catch (Exception e) {
            Throwable t = e;

            // If the startup detection is opened, the Exception is thrown directly.
            boolean check = getUrl().getParameter(Constants.CHECK_KEY, true)
                    && url.getParameter(Constants.CHECK_KEY, true)
                    && !CONSUMER_PROTOCOL.equals(url.getProtocol());
            boolean skipFailback = t instanceof SkipFailbackWrapperException;
            if (check || skipFailback) {
                if (skipFailback) {
                    t = t.getCause();
                }
                throw new IllegalStateException("Failed to register " + url + " to registry " + getUrl().getAddress() + ", cause: " + t.getMessage(), t);
            } else {
                logger.error("Failed to register " + url + ", waiting for retry, cause: " + t.getMessage(), t);
            }

            // Record a failed registration request to a failed list, retry regularly
            addFailedRegistered(url);
        }
    }

    protected abstract void doRegister(URL url);

   //ZookeeperRegistry
    public void doRegister(URL url) {
            try {
                //通过 Zookeeper 客户端创建节点，节点路径由 toUrlPath 方法生成
                zkClient.create(toUrlPath(url), url.getParameter(DYNAMIC_KEY, true));
            } catch (Throwable e) {
                throw new RpcException("Failed to register " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
            }
        }
```
到此关于服务注册的过程就分析完了。整个过程可简单总结为：先创建注册中心实例，之后再通过注册中心实例注册服务。