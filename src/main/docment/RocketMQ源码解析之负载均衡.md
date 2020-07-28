# RocketMQ源码解析之负载均衡
RocketMQ中的负载均衡都在Client端完成，具体来说的话，主要可以分为Producer端发送消息时候的负载均衡和Consumer端订阅消息的负载均衡。
## Producer 的负载均衡
> Producer端在发送消息的时候，会先根据Topic找到指定的TopicPublishInfo，在获取了TopicPublishInfo路由信息后，RocketMQ的客户端在默认方式下selectOneMessageQueue()方法会从TopicPublishInfo中的messageQueueList中选择一个队列（MessageQueue）进行发送消息。具体的容错策略均在MQFaultStrategy这个类中定义。这里有一个sendLatencyFaultEnable开关变量，如果开启，在随机递增取模的基础上，再过滤掉not available的Broker代理。所谓的"latencyFaultTolerance"，是指对之前失败的，按一定的时间做退避。例如，如果上次请求的latency超过550Lms，就退避3000Lms；超过1000L，就退避60000L；如果关闭，采用随机递增取模的方式选择一个队列（MessageQueue）来发送消息，latencyFaultTolerance机制是实现消息发送高可用的核心关键所在。

RocketMQ 中 所有在服务端创建的 topic 都会被发布到 NameServer 供客户端查询使用，同时一个 topic 可以存在在多个 brokerServer 上，TopicPublishInfo 不仅包含 topic 在哪个 broker 中，以及 topic 的存在broker路由信息，还包含了如果客户端不使用负载均衡发送消息时，默认使用的数据获取随机 MessageQueue 的服务。 
 ```java
public class TopicPublishInfo {
    private boolean orderTopic = false; //顺序 Topic
    private boolean haveTopicRouterInfo = false; //是否有路由信息
    private List<MessageQueue> messageQueueList = new ArrayList<MessageQueue>(); //当前 topic 的 MessageQueue
    private volatile ThreadLocalIndex sendWhichQueue = new ThreadLocalIndex(); //提供一个线程安全的Integer随机数获取服务
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
            int timesTotal = communicationMode == CommunicationMode.SYNC ? 1 + this.defaultMQProducer.getRetryTimesWhenSendFailed() : 1;
            int times = 0;
            String[] brokersSent = new String[timesTotal];
            for (; times < timesTotal; times++) {
                String lastBrokerName = null == mq ? null : mq.getBrokerName();
                MessageQueue mqSelected = this.selectOneMessageQueue(topicPublishInfo, lastBrokerName); //寻找发送消息的 MessageQueue
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