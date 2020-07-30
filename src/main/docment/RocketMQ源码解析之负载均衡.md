# RocketMQ源码解析之负载均衡
RocketMQ中的负载均衡都在Client端完成，具体来说的话，主要可以分为Producer端发送消息时候的负载均衡和Consumer端订阅消息的负载均衡。
## Producer 的负载均衡
> Producer端在发送消息的时候，会先根据Topic找到指定的TopicPublishInfo，在获取了TopicPublishInfo路由信息后，RocketMQ的客户端在默认方式下selectOneMessageQueue()方法会从TopicPublishInfo中的messageQueueList中选择一个队列（MessageQueue）进行发送消息。具体的容错策略均在MQFaultStrategy这个类中定义。这里有一个sendLatencyFaultEnable开关变量，如果开启，在随机递增取模的基础上，再过滤掉not available的Broker代理。所谓的"latencyFaultTolerance"，是指对之前失败的，按一定的时间做退避。例如，如果上次请求的latency超过550Lms，就退避3000Lms；超过1000L，就退避60000L；如果关闭，采用随机递增取模的方式选择一个队列（MessageQueue）来发送消息，latencyFaultTolerance机制是实现消息发送高可用的核心关键所在。

RocketMQ 中 所有在服务端创建的 topic 都会被发布到 NameServer 供客户端查询使用，同时一个 topic 可以存在在多个 broker 上，TopicPublishInfo 不仅包含 topic 在哪个 broker 中，以及 topic 的存在broker路由信息，还包含了如果客户端不使用负载均衡发送消息时，默认使用的数据获取随机 MessageQueue 的服务。 
 ```java
public class TopicPublishInfo {
    private boolean orderTopic = false; //顺序 Topic
    private boolean haveTopicRouterInfo = false; //是否有路由信息
    //当前 topic 的 MessageQueue    
    private List<MessageQueue> messageQueueList = new ArrayList<MessageQueue>();
    //提供一个线程安全的Integer随机数获取服务    
    private volatile ThreadLocalIndex sendWhichQueue = new ThreadLocalIndex(); 
    private TopicRouteData topicRouteData; //有 topic 的broker路由信息
    public boolean ok() {
        return null != this.messageQueueList && !this.messageQueueList.isEmpty();
    }
    //通过BrokerName指定消息要发送的 Broker 服务器，即获取 BrokerName 指定的服务器上的随机一个 MessageQueue
    public MessageQueue selectOneMessageQueue(final String lastBrokerName) {
        if (lastBrokerName == null) { //没有指定BrokerName，随机获取一个 MessageQueue
            return selectOneMessageQueue();
        } else {
            int index = this.sendWhichQueue.getAndIncrement();//获取数据数
            for (int i = 0; i < this.messageQueueList.size(); i++) {
                int pos = Math.abs(index++) % this.messageQueueList.size(); //获取不越界的随机数
                if (pos < 0)
                    pos = 0;
                MessageQueue mq = this.messageQueueList.get(pos);
                if (!mq.getBrokerName().equals(lastBrokerName)) { //通过 BrokerName 指定要返回的 MessageQueue
                    return mq;
                }
            }
            return selectOneMessageQueue();
        }
    }

    public MessageQueue selectOneMessageQueue() {
        int index = this.sendWhichQueue.getAndIncrement(); //获取随机数
        int pos = Math.abs(index) % this.messageQueueList.size(); //获取不越界的随机数
        if (pos < 0)
            pos = 0;
        return this.messageQueueList.get(pos); //随机获取一个 MessageQueue
    }
}
```
当RocketMQ在发送消息时，Producer 会获取 Message 中的 topic 的信息，找到跟 topic 相关的发布路由信息TopicPublishInfo。
```java
    private SendResult sendDefaultImpl(
        Message msg,
        final CommunicationMode communicationMode,
        final SendCallback sendCallback,
        final long timeout
    ) throws MQClientException, RemotingException, MQBrokerException, InterruptedException {
        this.makeSureStateOK();
        Validators.checkMessage(msg, this.defaultMQProducer);
        final long invokeID = random.nextLong();
        long beginTimestampFirst = System.currentTimeMillis();
        long beginTimestampPrev = beginTimestampFirst;
        long endTimestamp = beginTimestampFirst;
        //获取TopicPublishInfo 信息 
        TopicPublishInfo topicPublishInfo = this.tryToFindTopicPublishInfo(msg.getTopic()); 
        if (topicPublishInfo != null && topicPublishInfo.ok()) { // topic发布信息可使用
            boolean callTimeout = false;
            MessageQueue mq = null;
            Exception exception = null;
            SendResult sendResult = null;
            int timesTotal = communicationMode == CommunicationMode.SYNC ? 
                            1 + this.defaultMQProducer.getRetryTimesWhenSendFailed() : 1;
            int times = 0;
            String[] brokersSent = new String[timesTotal];
            for (; times < timesTotal; times++) {
                String lastBrokerName = null == mq ? null : mq.getBrokerName();
                //寻找发送消息的 MessageQueue
                MessageQueue mqSelected = this.selectOneMessageQueue(topicPublishInfo, lastBrokerName); 
                if (mqSelected != null) {
                    mq = mqSelected;
                    brokersSent[times] = mq.getBrokerName();
                    try {
                        beginTimestampPrev = System.currentTimeMillis();
                        if (times > 0) {
                            //Reset topic with namespace during resend.
                            msg.setTopic(this.defaultMQProducer.withNamespace(msg.getTopic()));
                        }
                        long costTime = beginTimestampPrev - beginTimestampFirst;
                        if (timeout < costTime) {
                            callTimeout = true;
                            break;
                        }

                        sendResult = this.sendKernelImpl(msg, mq, communicationMode, sendCallback, topicPublishInfo, timeout - costTime);
                        endTimestamp = System.currentTimeMillis();
                        this.updateFaultItem(mq.getBrokerName(), endTimestamp - beginTimestampPrev, false);//更新 latencyFaultTolerance 的 latency 时间
                    //……
    }

```
如果在本地的 topicPublishInfoTable 已存在并且是可用的 ，则直接使用，如果不存在，则先在本地加入一个空的topic发布信息，再向 NameServer 获取 topic 的路由信息更新本地 topicPublishInfoTable 信息。
```java
private TopicPublishInfo tryToFindTopicPublishInfo(final String topic) {
        TopicPublishInfo topicPublishInfo = this.topicPublishInfoTable.get(topic);//从本地topic发布信息表中获取
        if (null == topicPublishInfo || !topicPublishInfo.ok()) { //如果本地不存在或者本地的 topicPublishInfo 是不可用的
            this.topicPublishInfoTable.putIfAbsent(topic, new TopicPublishInfo()); //在本地增加一个空
            this.mQClientFactory.updateTopicRouteInfoFromNameServer(topic); //通过远程 NameServer 更新本地 topic 相关信息
            topicPublishInfo = this.topicPublishInfoTable.get(topic);
        }
        if (topicPublishInfo.isHaveTopicRouterInfo() || topicPublishInfo.ok()) {
            return topicPublishInfo; //返回本地可用
        } else {//如果指定的 topic 不存在，则通过 defaultMQProducer 默认的“TBW102” 进行再次获取 topic 发布信息
            this.mQClientFactory.updateTopicRouteInfoFromNameServer(topic, true, this.defaultMQProducer);
            topicPublishInfo = this.topicPublishInfoTable.get(topic);
            return topicPublishInfo;
        }
    }
```
获取可使用的 topic 发布信息后， 则通过 `selectOneMessageQueue()` 负载均衡获取一个发送消息的队列 MessageQueue
```java
public MessageQueue selectOneMessageQueue(final TopicPublishInfo tpInfo, final String lastBrokerName) {
        return this.mqFaultStrategy.selectOneMessageQueue(tpInfo, lastBrokerName);
    }
```
selectOneMessageQueue() 通过 MQFaultStrategy 通过 latencyFaultTolerance 进行负载均衡，latencyFaultTolerance是指对之前失败的，按一定的时间做退避。例如，如果上次请求的latency超过550Lms，就退避3000Lms；超过1000L，就退避60000L；
整个 MQFaultStrategy 的 selectOneMessageQueue 过程如下：
![在这里插入图片描述](https://img-blog.csdnimg.cn/20200728130217916.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L2t3eHl6aw==,size_16,color_FFFFFF,t_70)

```java
public class MQFaultStrategy {
    private final static InternalLogger log = ClientLogger.getLog();
    private final LatencyFaultTolerance<String> latencyFaultTolerance = new LatencyFaultToleranceImpl(); //负载均很策略
    private boolean sendLatencyFaultEnable = false; //是否开始，默认不开启

    private long[] latencyMax = {50L, 100L, 550L, 1000L, 2000L, 3000L, 15000L}; //latency 时间的标准时间
    private long[] notAvailableDuration = {0L, 0L, 30000L, 60000L, 120000L, 180000L, 600000L};//退避时间

    //选出 指定BrokerName下的一个 MessageQueue 
    public MessageQueue selectOneMessageQueue(final TopicPublishInfo tpInfo, final String lastBrokerName) {
        if (this.sendLatencyFaultEnable) { //如果开启 latencyFaultTolerance 策略
            try {
                int index = tpInfo.getSendWhichQueue().getAndIncrement(); //随机获取一个数据数
                for (int i = 0; i < tpInfo.getMessageQueueList().size(); i++) {
                    int pos = Math.abs(index++) % tpInfo.getMessageQueueList().size();
                    if (pos < 0)
                        pos = 0;
                    MessageQueue mq = tpInfo.getMessageQueueList().get(pos);//先通过随机算法获取一个 MessageQueue 
                    //通过latencyFaultTolerance 判断当前 broker 是否已过退避时间
                    //如果已过退避时间，则表示当前 MessageQueue 处于可用状态
                    if (latencyFaultTolerance.isAvailable(mq.getBrokerName())) { 
                         //如果选出的 MessageQueue 所处的 broker 就是指定的 broker，则此 MessageQueue 就是最终选出的 MessageQueue
                        if (null == lastBrokerName || mq.getBrokerName().equals(lastBrokerName))
                            return mq;
                    }
                }
                //如果 TopicPublishInfo 中所有的 MessageQueue 都不可用，则获取一个响应最好的 broker 创建一个信息 MessageQueue
                final String notBestBroker = latencyFaultTolerance.pickOneAtLeast();//选出最优的Broker——即最小的回避时间，如果回避时间一样则选择最大的开始时间(回避到期时间最小)
                int writeQueueNums = tpInfo.getQueueIdByBroker(notBestBroker);//获取最优 broker 的写消息的队列的格式 
                if (writeQueueNums > 0) {//如果最优 broker 下存在可以入的队列
                    final MessageQueue mq = tpInfo.selectOneMessageQueue(); //随机获取一个 MessageQueue 对象
                    if (notBestBroker != null) { //如果 最优 broker 存在则新建一个 MessageQueue
                        mq.setBrokerName(notBestBroker);
                        mq.setQueueId(tpInfo.getSendWhichQueue().getAndIncrement() % writeQueueNums);
                    }
                    return mq; //返回新的MessageQueue
                } else {
                    latencyFaultTolerance.remove(notBestBroker);//如果没有可写的队列，则从策略中移除当前 broker
                }
            } catch (Exception e) {
                log.error("Error occurred when selecting message queue", e);
            }

            return tpInfo.selectOneMessageQueue();
        }

        return tpInfo.selectOneMessageQueue(lastBrokerName);//如果没有开启 使用随机获取 BrokerName 下的一个MessageQueue
    }
}
```

 RocketMQ中 `latencyFaultTolerance` 中主要是保存回避时间信息和使用回避时间，开始时间判断MessageQueue的可用以及回避时间的更新操作
 ```java
public class LatencyFaultToleranceImpl implements LatencyFaultTolerance<String> {
    private final ConcurrentHashMap<String, FaultItem> faultItemTable = new ConcurrentHashMap<String, FaultItem>(16);//记录故障项
    private final ThreadLocalIndex whichItemWorst = new ThreadLocalIndex();
    
    //更新
    public void updateFaultItem(final String name, final long currentLatency, final long notAvailableDuration) {
        FaultItem old = this.faultItemTable.get(name); //获取 name（brokerName） 的故障项
        if (null == old) {//不存在则创建 broker 的故障项
            final FaultItem faultItem = new FaultItem(name); //根据 broker 的name 创建
            faultItem.setCurrentLatency(currentLatency); //退避时间
            faultItem.setStartTimestamp(System.currentTimeMillis() + notAvailableDuration); //退避结束的开始时间

            old = this.faultItemTable.putIfAbsent(name, faultItem); //存入table
            if (old != null) { //如果已经存在
                old.setCurrentLatency(currentLatency); //更新退避时间
                old.setStartTimestamp(System.currentTimeMillis() + notAvailableDuration); //退避结束的开始时间
            }
        } else { //存在 broker 的故障项
            old.setCurrentLatency(currentLatency); //更新退避时间
            old.setStartTimestamp(System.currentTimeMillis() + notAvailableDuration); //退避结束的开始时间
        }
    }

    //判断 broker 是否可用
    public boolean isAvailable(final String name) {
        final FaultItem faultItem = this.faultItemTable.get(name); //获取 broker 的故障项
        if (faultItem != null) {
            return faultItem.isAvailable(); //判断当前时间是否大于等于退避结束时间
        }
        return true;
    }

    //移除 name 的broker 的故障项
    public void remove(final String name) { 
        this.faultItemTable.remove(name);
    }

    @Override
    public String pickOneAtLeast() {
        final Enumeration<FaultItem> elements = this.faultItemTable.elements();
        List<FaultItem> tmpList = new LinkedList<FaultItem>();
        while (elements.hasMoreElements()) {
            final FaultItem faultItem = elements.nextElement();
            tmpList.add(faultItem);
        }

        if (!tmpList.isEmpty()) {
            Collections.shuffle(tmpList);

            Collections.sort(tmpList);

            final int half = tmpList.size() / 2;
            if (half <= 0) {
                return tmpList.get(0).getName();
            } else {
                final int i = this.whichItemWorst.getAndIncrement() % half;
                return tmpList.get(i).getName();
            }
        }

        return null;
    }


    //故障项对象，主要是保存broker的需要退避的时间和退避后的开始时间
    class FaultItem implements Comparable<FaultItem> {
        private final String name;//broker 名称
        private volatile long currentLatency; //退避时间
        private volatile long startTimestamp; //过了退避期的开始时间

        public FaultItem(final String name) {
            this.name = name;
        }

        //判断最优，先比较是否可用，如果当前对象可用，则最优；可用性相同则比较退避时间，退避时间越短则最优；
        //退避时间相等则比较退出退避的开始时间，谁先退出——即开始可用谁最优
        public int compareTo(final FaultItem other) {
            if (this.isAvailable() != other.isAvailable()) {
                if (this.isAvailable())
                    return -1;
                if (other.isAvailable())
                    return 1;
            }
            if (this.currentLatency < other.currentLatency)
                return -1;
            else if (this.currentLatency > other.currentLatency) {
                return 1;
            }
            if (this.startTimestamp < other.startTimestamp)
                return -1;
            else if (this.startTimestamp > other.startTimestamp) {
                return 1;
            }
            return 0;
        }
        
        //当前时间是否大于回避时间
        public boolean isAvailable() {
            return (System.currentTimeMillis() - startTimestamp) >= 0;
        }
    }
}

```

## Consumer 的负载均衡
> 在RocketMQ中，Consumer端的两种消费模式（Push/Pull）都是基于拉模式来获取消息的，而在Push模式只是对pull模式的一种封装，其本质实现为消息拉取线程在从服务器拉取到一批消息后，然后提交到消息消费线程池后，又“马不停蹄”的继续向服务器再次尝试拉取消息。如果未拉取到消息，则延迟一下又继续拉取。在两种基于拉模式的消费方式（Push/Pull）中，均需要Consumer端在知道从Broker端的哪一个消息队列—队列中去获取消息。因此，有必要在Consumer端来做负载均衡，即Broker端中多个MessageQueue分配给同一个ConsumerGroup中的哪些Consumer消费。

Broker 端保存Consumer的订阅信息：Broker 收到 Consumer 发送的心跳消息后，会将订阅元数据保存到 ConsumerManager 的 topicConfigTable 中，在 topicConfigTable 保存客户端的通信信息和客户端订阅MQ的元数据
```java
    public RemotingCommand heartBeat(ChannelHandlerContext ctx, RemotingCommand request) {
        RemotingCommand response = RemotingCommand.createResponseCommand(null);
        HeartbeatData heartbeatData = HeartbeatData.decode(request.getBody(), HeartbeatData.class);//解码心跳数据
        ClientChannelInfo clientChannelInfo = new ClientChannelInfo(
            ctx.channel(),
            heartbeatData.getClientID(),
            request.getLanguage(),
            request.getVersion()
        );//获取客户端信息
        //解析客户端的 Consumer 的订阅元数据
        for (ConsumerData data : heartbeatData.getConsumerDataSet()) {
            //获取 broker 中 Consumer 的订阅元数据
            SubscriptionGroupConfig subscriptionGroupConfig =
                this.brokerController.getSubscriptionGroupManager().findSubscriptionGroupConfig(
                    data.getGroupName());
            boolean isNotifyConsumerIdsChangedEnable = true;
            if (null != subscriptionGroupConfig) { //如果已存在订阅消息
                //是否需要更新 Consumer 的id
                isNotifyConsumerIdsChangedEnable = subscriptionGroupConfig.isNotifyConsumerIdsChangedEnable(); 
                int topicSysFlag = 0;
                if (data.isUnitMode()) {
                    topicSysFlag = TopicSysFlag.buildSysFlag(false, true);
                }
                //创建 retry 的 topic，如果已存在直接返回
                String newTopic = MixAll.getRetryTopic(data.getGroupName());
                this.brokerController.getTopicConfigManager().createTopicInSendMessageBackMethod(
                    newTopic,
                    subscriptionGroupConfig.getRetryQueueNums(),
                    PermName.PERM_WRITE | PermName.PERM_READ, topicSysFlag);
            }
            //注册保存 Consumer 的订阅元数据
            boolean changed = this.brokerController.getConsumerManager().registerConsumer(
                data.getGroupName(), //消费组名
                clientChannelInfo, //消费的客户端信息
                data.getConsumeType(), //消费方式 pull/push
                data.getMessageModel(), //消费模式 广播/集群
                data.getConsumeFromWhere(), //消费指针 接着上次消费/重头消费/指定时间消费等
                data.getSubscriptionDataSet(),//订阅元数据 包含订阅的topic ，tag等信息
                isNotifyConsumerIdsChangedEnable 
            );

            if (changed) {
                log.info("registerConsumer info changed {} {}",
                    data.toString(),
                    RemotingHelper.parseChannelRemoteAddr(ctx.channel())
                );
            }
        }

        for (ProducerData data : heartbeatData.getProducerDataSet()) {
            this.brokerController.getProducerManager().registerProducer(data.getGroupName(),
                clientChannelInfo);
        }
        response.setCode(ResponseCode.SUCCESS);
        response.setRemark(null);
        return response;
    }

//ConsumerManager
public boolean registerConsumer(final String group, final ClientChannelInfo clientChannelInfo,
        ConsumeType consumeType, MessageModel messageModel, ConsumeFromWhere consumeFromWhere,
        final Set<SubscriptionData> subList, boolean isNotifyConsumerIdsChangedEnable) {

        ConsumerGroupInfo consumerGroupInfo = this.consumerTable.get(group); //是否消费组的消费信息
        if (null == consumerGroupInfo) { //不存在则保存新的  ConsumerGroupInfo
            ConsumerGroupInfo tmp = new ConsumerGroupInfo(group, consumeType, messageModel, consumeFromWhere);
            ConsumerGroupInfo prev = this.consumerTable.putIfAbsent(group, tmp); //保存新的组的消费信息
            consumerGroupInfo = prev != null ? prev : tmp;
        }
        //更新 consumerGroupInfo 信息
        boolean r1 = consumerGroupInfo.updateChannel(clientChannelInfo, consumeType, messageModel, consumeFromWhere);
        boolean r2 = consumerGroupInfo.updateSubscription(subList); //更新订阅的元数据信息

        if (r1 || r2) {
            if (isNotifyConsumerIdsChangedEnable) {
                this.consumerIdsChangeListener.handle(ConsumerGroupEvent.CHANGE, group, consumerGroupInfo.getAllChannel());
            }
        }
        this.consumerIdsChangeListener.handle(ConsumerGroupEvent.REGISTER, group, subList);

        return r1 || r2;
    }
    
    /**
     * 更新客户端通信信息
     */       
    public boolean updateChannel(final ClientChannelInfo infoNew, ConsumeType consumeType,
        MessageModel messageModel, ConsumeFromWhere consumeFromWhere) {
        boolean updated = false;
        this.consumeType = consumeType;
        this.messageModel = messageModel;
        this.consumeFromWhere = consumeFromWhere;
        //找到 客户端的通信 channel 的客户端信息    
        ClientChannelInfo infoOld = this.channelInfoTable.get(infoNew.getChannel()); 
        if (null == infoOld) {
            //没有的话直接存入新的 channel 的客户端信息
            ClientChannelInfo prev = this.channelInfoTable.put(infoNew.getChannel(), infoNew); 
            if (null == prev) {
                log.info("new consumer connected, group: {} {} {} channel: {}", this.groupName, consumeType,
                    messageModel, infoNew.toString());
                updated = true;
            }

            infoOld = infoNew;
        } else {
            //如果已经存在了 channel 判断 channel 是否已经发生变化，变化了则更新客户端信息   
            if (!infoOld.getClientId().equals(infoNew.getClientId())) { 
                log.error("[BUG] consumer channel exist in broker, but clientId not equal. GROUP: {} OLD: {} NEW: {} ",
                    this.groupName,
                    infoOld.toString(),
                    infoNew.toString());
                this.channelInfoTable.put(infoNew.getChannel(), infoNew);
            }
        }

        this.lastUpdateTimestamp = System.currentTimeMillis();
        infoOld.setLastUpdateTimestamp(this.lastUpdateTimestamp);

        return updated;
    }
    
    /**
     * 更新Consumer的订阅元数据
     */
    public boolean updateSubscription(final Set<SubscriptionData> subList) {
        boolean updated = false;

        for (SubscriptionData sub : subList) {
            SubscriptionData old = this.subscriptionTable.get(sub.getTopic()); //获取 topic的订阅数据
            if (old == null) {
                //不存在topic的订阅数据则更新
                SubscriptionData prev = this.subscriptionTable.putIfAbsent(sub.getTopic(), sub); 
                if (null == prev) {
                    updated = true;
                    log.info("subscription changed, add new topic, group: {} {}",
                        this.groupName,
                        sub.toString());
                }
            //存在的话则比较版本号，如果是新的版本号，则更新为新的版本号
            } else if (sub.getSubVersion() > old.getSubVersion()) { 
                
                if (this.consumeType == ConsumeType.CONSUME_PASSIVELY) {
                    log.info("subscription changed, group: {} OLD: {} NEW: {}",
                        this.groupName,
                        old.toString(),
                        sub.toString()
                    );
                }

                this.subscriptionTable.put(sub.getTopic(), sub);
            }
        }
        //移除已经过时的订阅元数据
        Iterator<Entry<String, SubscriptionData>> it = this.subscriptionTable.entrySet().iterator();
        while (it.hasNext()) {
            Entry<String, SubscriptionData> next = it.next();
            String oldTopic = next.getKey();
            boolean exist = false;
            for (SubscriptionData sub : subList) {
                if (sub.getTopic().equals(oldTopic)) {
                    exist = true;
                    break;
                }
            }
            if (!exist) {
                log.warn("subscription changed, group: {} remove topic {} {}",
                    this.groupName,
                    oldTopic,
                    next.getValue().toString()
                );

                it.remove();
                updated = true;
            }
        }
        this.lastUpdateTimestamp = System.currentTimeMillis();
        return updated;
    }

```

这样客户端的 Consumer 信息就保存到了 broker 中，broker 保存的消息提供给客户端进行负载均衡使用，当 Consumer 实例在启动的时候，会完成负载均衡服务线程—RebalanceService的启动（每隔20s执行一次）。
```java
//MQClientInstance.java
    public void start() throws MQClientException {
        synchronized (this) {
            switch (this.serviceState) {
                case CREATE_JUST:
                    this.serviceState = ServiceState.START_FAILED;
                    // If not specified,looking address from name server
                    if (null == this.clientConfig.getNamesrvAddr()) {
                        this.mQClientAPIImpl.fetchNameServerAddr();
                    }
                    // Start request-response channel
                    this.mQClientAPIImpl.start();
                    // Start various schedule tasks
                    this.startScheduledTask();
                    // Start pull service
                    this.pullMessageService.start();
                    // Start rebalance service
                    this.rebalanceService.start(); //负载均衡启动
                    // Start push service
                    this.defaultMQProducer.getDefaultMQProducerImpl().start(false);
                    log.info("the client factory [{}] start OK", this.clientId);
                    this.serviceState = ServiceState.RUNNING;
                    break;
                case START_FAILED:
                    throw new MQClientException("The Factory object[" + this.getClientId() + "] has been created before, and failed.", null);
                default:
                    break;
            }
        }
    }

//RebalanceService.java
    public void run() {
        log.info(this.getServiceName() + " service started");
        while (!this.isStopped()) {
            this.waitForRunning(waitInterval);
            this.mqClientFactory.doRebalance();
        }
        log.info(this.getServiceName() + " service end");
    }
```
RebalanceService 线程的run()方法最终调用的是 RebalanceImpl 类的rebalanceByTopic()方法，该方法是实现Consumer端负载均衡的核心。ebalanceByTopic()方法会根据消费者通信类型为“广播模式”还是“集群模式”做不同的逻辑处理。这里主要来看下集群模式下的主要处理流程：
   1. 从rebalanceImpl实例的获取本地的本地的订阅信息subscriptionInner，在后通过本地的订阅 topic 在本地缓存变量—topicSubscribeInfoTable中，获取该Topic主题下的消息消费队列集合（mqSet）。
   2. 集群模式下根据topic和consumerGroup为参数调用mQClientFactory.findConsumerIdList()方法向Broker端获取该消费组下消费者Id列表；广播模式不需要知道具体的该消费组下消费者，因为所有的消费者都要参与消费。
   3. 先对Topic下的消息消费队列、消费者Id排序，然后用消息队列分配策略算法（默认为：消息队列的平均分配算法），计算出待拉取的消息队列。
   4. 然后，调用updateProcessQueueTableInRebalance()方法，具体的做法是，先将分配到的消息队列集合（mqSet）与processQueueTable做一个过滤比对。
   ![在这里插入图片描述](https://img-blog.csdnimg.cn/20200729161033596.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L2t3eHl6aw==,size_16,color_FFFFFF,t_70)
   红色部分，表示与分配到的消息队列集合mqSet互不包含的部分，设置ProcessQueue 的 Dropped属性为true，然后进行移除操作；绿色部分，表示与分配到的消息队列集合mqSet的交集但ProcessQueue已经过期，在Push模式的，设置ProcessQueue 的 Dropped属性为true，然后进行移除操作。
   5. 最后，为过滤后的消息队列集合（mqSet）中的每个MessageQueue创建一个ProcessQueue对象并存入RebalanceImpl的processQueueTable队列中（其中调用RebalanceImpl实例的computePullFromWhere(MessageQueue mq)方法获取该MessageQueue对象的下一个进度消费值offset，随后填充至接下来要创建的pullRequest对象属性中），并创建拉取请求对象—pullRequest添加到拉取列表—pullRequestList中，最后执行dispatchPullRequest()方法，将Pull消息的请求对象PullRequest依次放入PullMessageService服务线程的阻塞队列pullRequestQueue中，待该服务线程取出后向Broker端发起Pull消息的请求。
   
```java
RebalanceImpl.java
    public void doRebalance(final boolean isOrder) {
        Map<String, SubscriptionData> subTable = this.getSubscriptionInner(); //获取本地的订阅信息
        if (subTable != null) {
            for (final Map.Entry<String, SubscriptionData> entry : subTable.entrySet()) {
                final String topic = entry.getKey();
                try {
                    this.rebalanceByTopic(topic, isOrder); //根据 topic 进行负载均衡信息
                } catch (Throwable e) {
                    if (!topic.startsWith(MixAll.RETRY_GROUP_TOPIC_PREFIX)) {
                        log.warn("rebalanceByTopic Exception", e);
                    }
                }
            }
        }

        this.truncateMessageQueueNotMyTopic();//移除不是客户端订阅的topic 信息
    }

    private void rebalanceByTopic(final String topic, final boolean isOrder) {
        switch (messageModel) {
            case BROADCASTING: { //广播模式
                Set<MessageQueue> mqSet = this.topicSubscribeInfoTable.get(topic); //获取订阅的topic的MessageQueue
                if (mqSet != null) {
                    //更新 ProcessQueueTable 中的信息
                    boolean changed = this.updateProcessQueueTableInRebalance(topic, mqSet, isOrder); 
                    if (changed) {
                        this.messageQueueChanged(topic, mqSet, mqSet);
                        log.info("messageQueueChanged {} {} {} {}",
                            consumerGroup,
                            topic,
                            mqSet,
                            mqSet);
                    }
                } else {
                    log.warn("doRebalance, {}, but the topic[{}] not exist.", consumerGroup, topic);
                }
                break;
            }
            case CLUSTERING: {//集群模式
                //获取订阅的topic的MessageQueue    
                Set<MessageQueue> mqSet = this.topicSubscribeInfoTable.get(topic); 
                //通过broker根据订阅组consumerGroup获取订阅了该topic 的 ConsumerId
                List<String> cidAll = this.mQClientFactory.findConsumerIdList(topic, consumerGroup);
                if (null == mqSet) {
                    if (!topic.startsWith(MixAll.RETRY_GROUP_TOPIC_PREFIX)) {
                        log.warn("doRebalance, {}, but the topic[{}] not exist.", consumerGroup, topic);
                    }
                }
                if (null == cidAll) {
                    log.warn("doRebalance, {} {}, get consumer id list failed", consumerGroup, topic);
                }
                if (mqSet != null && cidAll != null) {
                    List<MessageQueue> mqAll = new ArrayList<MessageQueue>();
                    mqAll.addAll(mqSet);

                    Collections.sort(mqAll); //将所有MessageQueue排序
                    Collections.sort(cidAll);//将所有消费端Consumer排序
                    /**
                     * AllocateMessageQueueStrategy 消息队列分配策略算法  
                     *机房下分配算法（机房内可以使用其他分配算法） AllocateMachineRoomNearby  
                     *一致的哈希队列算法 AllocateMessageQueueConsistentHash
                     *平均分配算法（默认使用） AllocateMessageQueueAveragely
                     *机房哈希队列算法 AllocateMessageQueueByMachineRoom
                     *指定分配 AllocateMessageQueueByConfig
                     *循环平均分配算法 AllocateMessageQueueAveragelyByCircle
                     */   
                    AllocateMessageQueueStrategy strategy = this.allocateMessageQueueStrategy; 

                    List<MessageQueue> allocateResult = null;
                    try {
                        //通过分配算法获取本ClientId 下分配到的 MessageQueue
                        allocateResult = strategy.allocate(
                            this.consumerGroup,
                            this.mQClientFactory.getClientId(),
                            mqAll,
                            cidAll);
                    } catch (Throwable e) {
                        log.error("AllocateMessageQueueStrategy.allocate Exception. allocateMessageQueueStrategyName={}", strategy.getName(),
                            e);
                        return;
                    }

                    Set<MessageQueue> allocateResultSet = new HashSet<MessageQueue>();
                    if (allocateResult != null) {
                        allocateResultSet.addAll(allocateResult);
                    }
                    //通过负载均衡到的 MessageQueue 更新 ProcessQueueTable 中的信息
                    boolean changed = this.updateProcessQueueTableInRebalance(topic, allocateResultSet, isOrder);
                    if (changed) {
                        log.info(
                            "rebalanced result changed. allocateMessageQueueStrategyName={}, group={}, topic={}, clientId={}, mqAllSize={}, cidAllSize={}, rebalanceResultSize={}, rebalanceResultSet={}",
                            strategy.getName(), consumerGroup, topic, this.mQClientFactory.getClientId(), mqSet.size(), cidAll.size(),
                            allocateResultSet.size(), allocateResultSet);
                        //如果 ProcessQueueTable 已经改动，则通知 push/pull 进行新的  ProcessQueueTable 处理操作
                        //push 下 更新拉取线程的 每次拉取Message个数（控制阈值），pull下不做处理
                        this.messageQueueChanged(topic, mqSet, allocateResultSet);
                    }
                }
                break;
            }
            default:
                break;
        }
    }
```      
AllocateMessageQueueStrategy 消息队列分配策略算法有很多的实现方式，我们这里看一下默认使用的策略——平均分配算法，这里的平均分配算法，类似于分页的算法，将所有MessageQueue排好序类似于记录，将所有消费端Consumer排好序类似页数，并求出每一页需要包含的平均size和每个页面记录的范围range，最后遍历整个range而计算出当前Consumer端应该分配到的记录（这里即为：MessageQueue）。
```java
public class AllocateMessageQueueAveragely implements AllocateMessageQueueStrategy {
    private final InternalLogger log = ClientLogger.getLog();

    @Override
    public List<MessageQueue> allocate(String consumerGroup, String currentCID, List<MessageQueue> mqAll,
        List<String> cidAll) {
        if (currentCID == null || currentCID.length() < 1) {//判断当前的client id 是否合法
            throw new IllegalArgumentException("currentCID is empty");
        }
        if (mqAll == null || mqAll.isEmpty()) {//判断topic下是否存在 MessageQueue
            throw new IllegalArgumentException("mqAll is null or mqAll empty");
        }
        if (cidAll == null || cidAll.isEmpty()) { //判断topic下是否存在消费端 consumer id
            throw new IllegalArgumentException("cidAll is null or cidAll empty");
        }

        List<MessageQueue> result = new ArrayList<MessageQueue>();
        //如果 topic 的所有消费者中不包含当前消费者，则当前消费者分配到空的消费，即不参与消费
        if (!cidAll.contains(currentCID)) {
            log.info("[BUG] ConsumerGroup: {} The consumerId: {} not in cidAll: {}",
                consumerGroup,
                currentCID,
                cidAll);
            return result;
        }

        int index = cidAll.indexOf(currentCID); //当前消费者的消费排位——当前页
        //整页分配后余下的个数，即 mqAll是否是cidAll整数倍，如果是整数倍的话则意味着所有页都是一样的，
        // 如果不能平均分配的话，则当前页多分配一条，当前页后用除数，则当前页前面的页当好多分配完余数
        int mod = mqAll.size() % cidAll.size(); 
        //如果 MessageQueue 个数小于等于消费者数量，则每页数设置为1
        //如果 MessageQueue 个数等于消费者数量的倍数，则每页数设置为倍数
        //如果 MessageQueue 个数不能为消费者数量的整除，则当前页前面的页中个数为除数+1个，后边页数为整除数
        int averageSize =
            mqAll.size() <= cidAll.size() ? 1 : (mod > 0 && index < mod ? mqAll.size() / cidAll.size()
                + 1 : mqAll.size() / cidAll.size());
        int startIndex = (mod > 0 && index < mod) ? index * averageSize : index * averageSize + mod; //当前页的起始游标
        int range = Math.min(averageSize, mqAll.size() - startIndex);//当前页的个数
        for (int i = 0; i < range; i++) {
            result.add(mqAll.get((startIndex + i) % mqAll.size()));
        }
        return result;
    }

    @Override
    public String getName() {
        return "AVG";
    }
}
```

通过负载均衡到的 MessageQueue 列表需要跟本地正在进行消费的 ProcessQueueTable 中执行消费的 ProcessQueue 列表进行对比筛选过滤， ProcessQueue 为MessageQueue的消费信息，如果 ProcessQueueTable 与分配到的消息队列集合 mqSet 做对比，将不包含在 mqSet 的 MessageQueue 的 ProcessQueue 移除 ProcessQueueTable ，如果包容部分的 ProcessQueue 有过期的，也需要进行移除
```java
    private boolean updateProcessQueueTableInRebalance(final String topic, final Set<MessageQueue> mqSet,
        final boolean isOrder) {
        boolean changed = false;
        Iterator<Entry<MessageQueue, ProcessQueue>> it = this.processQueueTable.entrySet().iterator();
        while (it.hasNext()) {
            Entry<MessageQueue, ProcessQueue> next = it.next();
            MessageQueue mq = next.getKey();
            ProcessQueue pq = next.getValue();
            if (mq.getTopic().equals(topic)) { // 是否是订阅 topic 下的 MessageQueue
                //当前  ProcessQueue 消费的 MessageQueue 是否存在分配到的 mqSet 中
                if (!mqSet.contains(mq)) { //不存在则设置 ProcessQueue 的 Dropped 为true 
                    pq.setDropped(true);
                    if (this.removeUnnecessaryMessageQueue(mq, pq)) { //判断是否需要移除消费
                        it.remove(); //移除消费
                        changed = true;
                        log.info("doRebalance, {}, remove unnecessary mq, {}", consumerGroup, mq);
                    }
                } else if (pq.isPullExpired()) { //判断 ProcessQueue 是否已过期
                    switch (this.consumeType()) { //不同模式下的过期操作
                        case CONSUME_ACTIVELY: //pull 下不用处理
                            break;
                        case CONSUME_PASSIVELY: //push 下需要移除处理
                            pq.setDropped(true);
                            if (this.removeUnnecessaryMessageQueue(mq, pq)) {
                                it.remove();
                                changed = true;
                                log.error("[BUG]doRebalance, {}, remove unnecessary mq, {}, because pull is pause, so try to fixed it",
                                    consumerGroup, mq);
                            }
                            break;
                        default:
                            break;
                    }
                }
            }
        }

        //初始化 PullRequest 请求
        List<PullRequest> pullRequestList = new ArrayList<PullRequest>(); 
        for (MessageQueue mq : mqSet) {
            if (!this.processQueueTable.containsKey(mq)) { //不再 processQueueTable 的不用处理
                if (isOrder && !this.lock(mq)) {
                    log.warn("doRebalance, {}, add a new mq failed, {}, because lock failed", consumerGroup, mq);
                    continue;
                }

                this.removeDirtyOffset(mq); //移除 MessageQueue 的消费指针
                ProcessQueue pq = new ProcessQueue();
                long nextOffset = this.computePullFromWhere(mq); //设置消费起始位置
                if (nextOffset >= 0) {
                    //重新放入 MessageQueue 的执行 ProcessQueue
                    ProcessQueue pre = this.processQueueTable.putIfAbsent(mq, pq); 
                    if (pre != null) { //已经存在的不做处理
                        log.info("doRebalance, {}, mq already exists, {}", consumerGroup, mq);
                    } else {
                        //新入 processQueueTable 的需要初始化 PullRequest 请求（供 push 消费模式使用）
                        log.info("doRebalance, {}, add a new mq, {}", consumerGroup, mq);
                        PullRequest pullRequest = new PullRequest();
                        pullRequest.setConsumerGroup(consumerGroup);
                        pullRequest.setNextOffset(nextOffset);
                        pullRequest.setMessageQueue(mq);
                        pullRequest.setProcessQueue(pq);
                        pullRequestList.add(pullRequest);
                        changed = true;
                    }
                } else {
                    log.warn("doRebalance, {}, add new mq failed, {}", consumerGroup, mq);
                }
            }
        }
        //分发 PullRequest 请求，pull不做处理， push 新开批量拉取 Message 线程 (pull 包装成 push)
        this.dispatchPullRequest(pullRequestList); 

        return changed;
    }
```


消息消费队列在同一消费组不同消费者之间的负载均衡，其核心设计理念是在一个消息消费队列在同一时间只允许被同一消费组内的一个消费者消费，一个消息消费者能同时消费多个消息队列。