# Zookeeper源码解析之选举机制
ZooKeeper 的使用一般来说都是集群的，ZooKeeper 的集群状态所示，集群部署时要选举出一台服务作为整个集群的领导者
![Zookeeper集群架构图](https://img-blog.csdnimg.cn/20200724163824945.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L2t3eHl6aw==,size_16,color_FFFFFF,t_70)
## 选举机制中的概念:
服务id :sid（id）,服务的标识

服务器中存放的最大数据ID ： zxid

选举/投票纪元：epoch，即第几轮选举

Server状态——选举状态：
  - LOOKING：竞选状态。
  - FOLLOWING：随从状态，同步leader状态，参与投票。
  - OBSERVING：观察状态,同步leader状态，不参与投票。
  - LEADING：领导者状态。
  
## 选举流程
1. 首先开始选举阶段，每个Server读取自身的sid，zxid，epoch(选举纪元，即第几轮选举)。
2. 发送投票信息
   1. 首先，每个Server第一轮都会投票给自己。
   2. 投票信息包含 ：所选举leader sid，zxid，epoch。epoch会随着选举轮数的增加而递增。
3. 接收投票信息
   1. 如果服务器B接收到服务器A的数据（服务器A处于选举状态(LOOKING 状态)  
      1. 首先，判断逻辑时钟值：
         - 如果发送过来的投票纪元epoch大于目前的服务自己的投票纪元。首先，更新本服务投票纪元，同时清空本服务投票纪元内收集到的来自其他server的选举数据。然后，判断是否需要更新当前自己的选举leader sid。判断规则：先比较epoch,大的胜出，再比较zxid，zxid大的胜出，再比较sid，sid大的胜出；然后再将自身最新的选举结果(也就是上面提到的三种数据（leader id，zxid，epoch）广播给其他server)
         - 如果发送过来的投票纪元epoch小于目前的投票纪元。说明对方server在一个相对较早的投票纪元中，则只需要将本机的三种数据（leader sid，zxid，epoch）发送给对方。　　　　
         - 如果发送过来的投票纪元epoch等于目前的投票纪元。再根据上述判断规则来选举出leader ，然后再将自身最新的选举结果(leader  sid，zxid，epoch）广播给其他server。
      2. 其次，判断服务器是不是已经收集到了所有服务器的选举状态：若是，根据选举结果设置自己的角色(FOLLOWING还是LEADER)，退出选举过程就是了。
   最后，若没有收到没有收集到所有服务器的选举状态：也可以判断一下根据以上过程之后最新的选举leader是不是得到了超过半数以上服务器的支持,如果是,那么尝试在200ms内接收一下数据,如果没有新的数据到来,说明大家都已经默认了这个结果,同样也设置角色退出选举过程。
   2. 如果所接收服务器A处在其它状态（FOLLOWING或者LEADING）
      1. 投票纪元epoch等于目前的投票纪元，将该数据保存到recvset。此时Server已经处于LEADING状态，说明此时这个server已经投票选出结果。若此时这个接收服务器宣称自己是leader, 那么将判断是不是有半数以上的服务器选举它，如果是则设置选举状态退出选举过程。
      2. 否则这是一条与当前投票纪元不符合的投票，那么说明在另一个选举过程中已经有了选举结果，于是将该选举结果加入到outofelection集合中，再根据outofelection来判断是否可以结束选举,如果可以结束则先保存投票纪元，设置选举状态，退出选举过程。
      
上边就是Zookeeper 的大概选举流程
![选举过程](https://img-blog.csdnimg.cn/20200724163929284.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L2t3eHl6aw==,size_16,color_FFFFFF,t_70)
## 选举源码
下边我们看一下 Zookeeper 的选举流程在代码中是如何实现，目前新版的的 Zookeeper 中只保留一个选举算法，`FastLeaderElection`:
```java
    public Vote lookForLeader() throws InterruptedException {
        try {
            self.jmxLeaderElectionBean = new LeaderElectionBean();
            MBeanRegistry.getInstance().register(self.jmxLeaderElectionBean, self.jmxLocalPeerBean);
        } catch (Exception e) {
            LOG.warn("Failed to register with JMX", e);
            self.jmxLeaderElectionBean = null;
        }

        self.start_fle = Time.currentElapsedTime();
        try {
            //当前选举纪元 leader 的选票保存
            Map<Long, Vote> recvset = new HashMap<Long, Vote>();

            //过期选举纪元 leander 的选票保存
            Map<Long, Vote> outofelection = new HashMap<Long, Vote>();
            int notTimeout = minNotificationInterval;
            synchronized (this) {
                logicalclock.incrementAndGet();//增加当前服务选举纪元
                updateProposal(getInitId(), getInitLastLoggedZxid(), getPeerEpoch());//投票自己做leader
            }
            sendNotifications();//向集群广播自己的投票
            SyncedLearnerTracker voteSet;

             //循环直到选出一个leader
            while ((self.getPeerState() == ServerState.LOOKING) && (!stop)) {
                //从接受到的投票中取出一个投票
                Notification n = recvqueue.poll(notTimeout, TimeUnit.MILLISECONDS);

                 //则发送更多投票信息，否则处理新的投票信息.
                if (n == null) {
                    if (manager.haveDelivered()) {
                        sendNotifications(); //广播自己的投票
                    } else {
                        manager.connectAll();//重新连接集群投票连接
                    }

                    //超时时间
                    int tmpTimeOut = notTimeout * 2;
                    notTimeout = Math.min(tmpTimeOut, maxNotificationInterval);
                    LOG.info("Notification time out: {}", notTimeout);
                } else if (validVoter(n.sid) && validVoter(n.leader)) {//sid是否在可参与投票选举对象内
                     //仅当投票来自当前或下一个纪元时才继续进行
                    switch (n.state) {
                    case LOOKING:
                        if (getInitLastLoggedZxid() == -1) {
                            LOG.debug("Ignoring notification as our zxid is -1");
                            break;
                        }
                        if (n.zxid == -1) {
                            LOG.debug("Ignoring notification from member with -1 zxid {}", n.sid);
                            break;
                        }
                        //如果投票信息中的投票纪元大于当前服务的投票纪元，则将服务纪元更新为投票信息中的纪元，并选出自己的投票投出 
                        if (n.electionEpoch > logicalclock.get()) {
                            logicalclock.set(n.electionEpoch);
                            recvset.clear();
                            //totalOrderPredicate leader选票比拼
                            //判断规则：先比较epoch,大的胜出，再比较zxid，zxid大的胜出，再比较sid，sid大的胜出    
                            if (totalOrderPredicate(n.leader, n.zxid, n.peerEpoch, getInitId(), getInitLastLoggedZxid(), getPeerEpoch())) {
                                updateProposal(n.leader, n.zxid, n.peerEpoch);//将收到的投票信息中的投票服务作为自己的leader投票
                            } else {
                                //继续坚持自己作为leader
                                updateProposal(getInitId(), getInitLastLoggedZxid(), getPeerEpoch());
                            }
                            sendNotifications();//广播自己的投票
                        } else if (n.electionEpoch < logicalclock.get()) { //如果当前投票信息中的投票纪元过期，则重新获取下一个的投票
                                LOG.debug(
                                    "Notification election epoch is smaller than logicalclock. n.electionEpoch = 0x{}, logicalclock=0x{}",
                                    Long.toHexString(n.electionEpoch),
                                    Long.toHexString(logicalclock.get()));
                            break;
                        } else if (totalOrderPredicate(n.leader, n.zxid, n.peerEpoch, proposedLeader, proposedZxid, proposedEpoch)) {//同纪元比拼
                            //如果投票中的服务胜出
                            updateProposal(n.leader, n.zxid, n.peerEpoch);//将收到的投票信息中的投票服务作为自己选举的leader投票
                            sendNotifications();//广播自己的投票
                        }

                        LOG.debug(
                            "Adding vote: from={}, proposed leader={}, proposed zxid=0x{}, proposed election epoch=0x{}",
                            n.sid,
                            n.leader,
                            Long.toHexString(n.zxid),
                            Long.toHexString(n.electionEpoch));

                        // 保存收到的投票
                        recvset.put(n.sid, new Vote(n.leader, n.zxid, n.electionEpoch, n.peerEpoch));
                        //获取集群内的所有投票信息
                        voteSet = getVoteTracker(recvset, new Vote(proposedLeader, proposedZxid, logicalclock.get(), proposedEpoch));

                        if (voteSet.hasAllQuorums()) {//是否收到所有的投票确认

                            // 在超时时间内等待投票
                            while ((n = recvqueue.poll(finalizeWait, TimeUnit.MILLISECONDS)) != null) {
                                if (totalOrderPredicate(n.leader, n.zxid, n.peerEpoch, proposedLeader, proposedZxid, proposedEpoch)) {
                                    recvqueue.put(n);//将收到的投票信息放入收到的投票队列中重新处理
                                    break;
                                }
                            }
                             //超时时间(200ms)内未收到新的投票，则投票结束
                            if (n == null) {
                                setPeerState(proposedLeader, voteSet);//判断当前服务在投票结束后的服务状态
                                Vote endVote = new Vote(proposedLeader, proposedZxid, logicalclock.get(), proposedEpoch);
                                leaveInstance(endVote);
                                return endVote; //返回并发送leanding状态的投票
                            }
                        }
                        break;
                    case OBSERVING:
                        LOG.debug("Notification from observer: {}", n.sid);
                        break;
                    case FOLLOWING:
                    case LEADING:
                        //通过服务器和投票在同一个投票纪元
                        if (n.electionEpoch == logicalclock.get()) {
                            recvset.put(n.sid, new Vote(n.leader, n.zxid, n.electionEpoch, n.peerEpoch, n.state));//保存收到的投票
                            //获取收到的投票信息，由于判断是否可以结束投票
                            voteSet = getVoteTracker(recvset, new Vote(n.version, n.leader, n.zxid, n.electionEpoch, n.peerEpoch, n.state));
                            if (voteSet.hasAllQuorums() && checkLeader(recvset, n.leader, n.electionEpoch)) {
                                setPeerState(n.leader, voteSet);//判断当前服务在投票结束后的服务状态
                                Vote endVote = new Vote(n.leader, n.zxid, n.electionEpoch, n.peerEpoch);
                                leaveInstance(endVote);
                                return endVote; /返回并发送leanding状态的投票
                            }
                        }

                        //如果投票不再同一个纪元
                        outofelection.put(n.sid, new Vote(n.version, n.leader, n.zxid, n.electionEpoch, n.peerEpoch, n.state));
                        //获取收到的投票信息，由于判断是否可以结束投票
                        voteSet = getVoteTracker(outofelection, new Vote(n.version, n.leader, n.zxid, n.electionEpoch, n.peerEpoch, n.state));
                        //是否收到所有的投票确认返回，和当前投票和自己的收到的投票是否都认投票中的leader
                        if (voteSet.hasAllQuorums() && checkLeader(outofelection, n.leader, n.electionEpoch)) {
                            synchronized (this) {
                                logicalclock.set(n.electionEpoch);
                                setPeerState(n.leader, voteSet);
                            }
                            Vote endVote = new Vote(n.leader, n.zxid, n.electionEpoch, n.peerEpoch);
                            leaveInstance(endVote);
                            return endVote;//返回leader的投票
                        }
                        break;
                    default:
                        LOG.warn("Notification state unrecoginized: {} (n.state), {}(n.sid)", n.state, n.sid);
                        break;
                    }
                } else {
                    if (!validVoter(n.leader)) {
                        LOG.warn("Ignoring notification for non-cluster member sid {} from sid {}", n.leader, n.sid);
                    }
                    if (!validVoter(n.sid)) {
                        LOG.warn("Ignoring notification for sid {} from non-quorum member sid {}", n.leader, n.sid);
                    }
                }
            }
            return null;
        } finally {
            try {
                if (self.jmxLeaderElectionBean != null) {
                    MBeanRegistry.getInstance().unregister(self.jmxLeaderElectionBean);
                }
            } catch (Exception e) {
                LOG.warn("Failed to unregister with JMX", e);
            }
            self.jmxLeaderElectionBean = null;
            LOG.debug("Number of connection processing threads: {}", manager.getConnectionThreadCount());
        }
    }

    private void setPeerState(long proposedLeader, SyncedLearnerTracker voteSet) {
        //如果当前选举的对象是自己，则设置自己状态为LEADING，否则根据服务配置判断服务状态为FOLLOWING或者OBSERVING
        ServerState ss = (proposedLeader == self.getId()) ? ServerState.LEADING : learningState();
        self.setPeerState(ss);
        if (ss == ServerState.LEADING) {//如果自己是LEADING
            leadingVoteSet = voteSet;//保存LEADING的投票信息
        }
    }
```
上边是投票的主流程处理，但不是全部的投票处理逻辑，有部分投票的接受判断在接收到投票消息响应的时候处理，但是投票的发送和接受使用多线程处理，下边我们看看投票的通信处理，主要看跟投票逻辑相关的内容，其他不相关内容忽略：
```java

protected class Messenger {
    Messenger(QuorumCnxManager manager) {
                this.ws = new WorkerSender(manager);
                this.wsThread = new Thread(this.ws, "WorkerSender[myid=" + self.getId() + "]");
                this.wsThread.setDaemon(true);
                this.wr = new WorkerReceiver(manager);
                this.wrThread = new Thread(this.wr, "WorkerReceiver[myid=" + self.getId() + "]");
                this.wrThread.setDaemon(true);
            }

    /**
     * 投票消息接受线程
     */
    class WorkerReceiver extends ZooKeeperThread {
        volatile boolean stop;
        QuorumCnxManager manager;
        WorkerReceiver(QuorumCnxManager manager) {
            super("WorkerReceiver");
            this.stop = false;
            this.manager = manager;
        }
        public void run() {
            Message response;
            while (!stop) {
                try {
                    response = manager.pollRecvQueue(3000, TimeUnit.MILLISECONDS);
                    //response 的解析和验证
                    //……
                    
                     //如果是没有资格参与投票选举的服务器，如observer，发送自己的投票
                    if (!validVoter(response.sid)) {
                        Vote current = self.getCurrentVote();
                        QuorumVerifier qv = self.getQuorumVerifier();
                        ToSend notmsg = new ToSend(
                            ToSend.mType.notification,
                            current.getId(),
                            current.getZxid(),
                            logicalclock.get(),
                            self.getPeerState(),
                            response.sid,
                            current.getPeerEpoch(),
                            qv.toString().getBytes());

                        sendqueue.offer(notmsg);
                    } else {
                        // 收到一个新的投票消息
                        LOG.debug("Receive new notification message. My id = {}", self.getId());
                        // 投票消息服务的判断
                        QuorumPeer.ServerState ackstate = QuorumPeer.ServerState.LOOKING;
                        switch (rstate) {
                        case 0:
                            ackstate = QuorumPeer.ServerState.LOOKING;
                            break;
                        case 1:
                            ackstate = QuorumPeer.ServerState.FOLLOWING;
                            break;
                        case 2:
                            ackstate = QuorumPeer.ServerState.LEADING;
                            break;
                        case 3:
                            ackstate = QuorumPeer.ServerState.OBSERVING;
                            break;
                        default:
                            continue;
                        }

                        n.leader = rleader;
                        n.zxid = rzxid;
                        n.electionEpoch = relectionEpoch;
                        n.state = ackstate;
                        n.sid = response.sid;
                        n.peerEpoch = rpeerepoch;
                        n.version = version;
                        n.qv = rqv;
                        //……

                        //如果自己服务状态为looking
                        if (self.getPeerState() == QuorumPeer.ServerState.LOOKING) {
                            recvqueue.offer(n);//接受投票
                             //如果发送投票的服务处在looking状态，则向它响应自己的选举投票
                            if ((ackstate == QuorumPeer.ServerState.LOOKING)
                                && (n.electionEpoch < logicalclock.get())) {
                                Vote v = getVote();
                                QuorumVerifier qv = self.getQuorumVerifier();
                                ToSend notmsg = new ToSend(
                                    ToSend.mType.notification,
                                    v.getId(),
                                    v.getZxid(),
                                    logicalclock.get(),
                                    self.getPeerState(),
                                    response.sid,
                                    v.getPeerEpoch(),
                                    qv.toString().getBytes());
                                sendqueue.offer(notmsg);
                            }
                        } else {
                             //如果当前服务不是looking状态，但它还得向发送投票的服务响应，响应当前集群选出的leader对象
                            Vote current = self.getCurrentVote();
                            if (ackstate == QuorumPeer.ServerState.LOOKING) {
                                if (self.leader != null) {
                                    if (leadingVoteSet != null) {
                                        self.leader.setLeadingVoteSet(leadingVoteSet);
                                        leadingVoteSet = null;
                                    }
                                    self.leader.reportLookingSid(response.sid);
                                }
                                QuorumVerifier qv = self.getQuorumVerifier();
                                ToSend notmsg = new ToSend(
                                    ToSend.mType.notification,
                                    current.getId(),
                                    current.getZxid(),
                                    current.getElectionEpoch(),
                                    self.getPeerState(),
                                    response.sid,
                                    current.getPeerEpoch(),
                                    qv.toString().getBytes());
                                sendqueue.offer(notmsg);
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    LOG.warn("Interrupted Exception while waiting for new message", e);
                }
            }
            LOG.info("WorkerReceiver is down");
        }

    }
    
    //发送线程
    class WorkerSender extends ZooKeeperThread {
        //比较简单，不做介绍
    }
}

```
        