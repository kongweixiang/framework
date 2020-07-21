# Zookeeper基础解析
ZooKeeper是一项集中式服务，用于维护配置信息，命名，提供分布式同步和提供组服务，ZooKeeper的目的是将不同服务的本质提炼成一个非常简单的界面，以实现集中式协调服务。
## ZooKeeper数据模型
ZooKeeper具有分层的名称空间，非常类似于分布式文件系统。唯一的区别是，名称空间中的每个节点都可以具有与其关联的数据以及子级。就像拥有一个文件系统一样，该文件系统也允许文件成为目录。  
任何的路径遵循以下约束：
 - 空字符（\ u0000）不能是路径名的一部分
 - 以下字符不能使用，因为它们不能很好地显示或以混乱的方式呈现： \ud800 - uF8FF, \uFFF0 - uFFFF
 - “.”能作为路径的一部分，但"."，“..”不能单独作为路径使用，因为Zookeeper不能使用相对路径
 - “zookeeper”被作为关键字保留

### ZNodes
ZooKeeper树中的每个节点都称为znode。Znodes维护一个统计信息结构，其中包括用于数据更改和acl更改的版本号，统计信息结构还带有时间戳，版本号和时间戳允许ZooKeeper验证缓存并协调更新。
```java
    public class DataNode implements Record {
        // 根据 path, data 和 stat 摘要内容
        private volatile long digest;
        // znode 摘要是否发生变化
        volatile boolean digestCached;
        //节点内容 
        byte[] data;
        /**
         * acl map 控制
         */
        Long acl;
        //持久化统计
        public StatPersisted stat;
        //子znode路径
        private Set<String> children = null;
    
        DataNode() {
            // default constructor
        }
    }

    //统计对象StatPersisted中主要包含一下
    public class StatPersisted implements Record {
      //在zookeeper中，节点的每一次变动都已一个zxid来进行记录((ZooKeeper 的事务 Id)
      private long czxid; //导致znode节点被创建的zxid
      private long mzxid; //znode节点的最后更新时间
      private long ctime; //znode 节点被创建的纪元时间-毫秒级
      private long mtime; //node节点的最后更新的纪元时间-毫秒级
      private int version; //节点版本号(更新次数)
      private int cversion; //子节点版本号
      private int aversion; //节点ACL版本号(ACL更新次数)
      private long ephemeralOwner; //如果znode是一个临时节点，则此znode的所有者的会话ID。如果它不是临时节点，那么它将为0
      private long pzxid; //最后更新次znode的子节点的zxid
      public StatPersisted() {
      }
    }
```
上边就是zookeepker节点树中节点的数据结构，一颗zookeepker节点树不仅包含节点，还包含监听Watches，数据访问权限控制等其他信息。  
DataTree 是 Zookeeper的数据树状结构对象：该树维护两个并行的数据结构：从中映射的哈希表数据节点的完整路径和数据节点树，对路径的所有访问是通过哈希表，仅在序列化到磁盘时遍历该树。
```java
    public class DataTree {
        private static final Logger LOG = LoggerFactory.getLogger(DataTree.class);
        private final RateLogger RATE_LOGGER = new RateLogger(LOG, 15 * 60 * 1000);
        //该映射提供了对数据节点的快速查找，是节点的内存数据
        private final NodeHashMap nodes;
        //节点监听管理器
        private IWatchManager dataWatches;
        //子节点监听管理器
        private IWatchManager childWatches;
        //节点个数
        private final AtomicLong nodeDataSize = new AtomicLong(0);
    
        /** zookeeper tree 的根节点 */
        private static final String rootZookeeper = "/";
    
        private static final String procZookeeper = Quotas.procZookeeper;
        private static final String procChildZookeeper = procZookeeper.substring(1);
        private static final String quotaZookeeper = Quotas.quotaZookeeper;
        private static final String quotaChildZookeeper = quotaZookeeper.substring(procZookeeper.length() + 1);
        private static final String configZookeeper = ZooDefs.CONFIG_NODE;
        private static final String configChildZookeeper = configZookeeper.substring(procZookeeper.length() + 1);
    
        /**
         * 配额节点的path树
         * 例如 /ab/bc/cf 将被分割保存的map为
         *           /
         *        ab/
         *        (ab)
         *      bc/
         *       /
         *      (bc)
         *   cf/
         *   (cf)
         */
        private final PathTrie pTrie = new PathTrie();
        public static final int STAT_OVERHEAD_BYTES = (6 * 8) + (5 * 4);
        /**
         * 临时对象
         */
        private final Map<Long, HashSet<String>> ephemerals = new ConcurrentHashMap<Long, HashSet<String>>();
        /**
         * 容器对象
         */
        private final Set<String> containers = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
        /**
         * ttl对象
         */
        private final Set<String> ttls = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
        private final ReferenceCountedACLCache aclCache = new ReferenceCountedACLCache();
        // The maximum number of tree digests that we will keep in our history
        public static final int DIGEST_LOG_LIMIT = 1024;
        // Dump digest every 128 txns, in hex it's 80, which will make it easier
        // to align and compare between servers.
        public static final int DIGEST_LOG_INTERVAL = 128;
        // If this is not null, we are actively looking for a target zxid that we
        // want to validate the digest for
        private ZxidDigest digestFromLoadedSnapshot;
        // The digest associated with the highest zxid in the data tree.
        private volatile ZxidDigest lastProcessedZxidDigest;
        private boolean firstMismatchTxn = true;
        // Will be notified when digest mismatch event triggered.
        private final List<DigestWatcher> digestWatchers = new ArrayList<>();
        private LinkedList<ZxidDigest> digestLog = new LinkedList<>();
    
        private final DigestCalculator digestCalculator;//摘要计算工具
        
        public DataTree() {
                this(new DigestCalculator());
        }
        DataTree(DigestCalculator digestCalculator) {
            this.digestCalculator = digestCalculator;//摘要计算工具
            nodes = new NodeHashMapImpl(digestCalculator);//内存节点分配
    
            //初始化根节点
            nodes.put("", root);
            nodes.putWithoutDigest(rootZookeeper, root);
    
            /**初始化系统节点和配额节点 */
            root.addChild(procChildZookeeper);
            nodes.put(procZookeeper, procDataNode);
            procDataNode.addChild(quotaChildZookeeper);
            nodes.put(quotaZookeeper, quotaDataNode);
            addConfigNode();//添加配置节点 /zookeeper/config，保存配置信息
            nodeDataSize.set(approximateDataSize());//得到节点长度
            try {
                dataWatches = WatchManagerFactory.createWatchManager();//初始化节点监听器
                childWatches = WatchManagerFactory.createWatchManager();//初始化子节点监听器
            } catch (Exception e) {
                LOG.error("Unexpected exception when creating WatchManager, exiting abnormally", e);
                ServiceUtils.requestSystemExit(ExitCode.UNEXPECTED_ERROR.getValue());
            }
        }        
    }
```

在DataTree中会涉及到ZooKeeper Sessions，ZooKeeper access control 和ZooKeeper Watches等的使用控制。  
ZooKeeper客户端通过使用语言绑定创建服务的句柄来与ZooKeeper服务建立会话。创建句柄后，该句柄将开始处于CONNECTING状态，并且客户端库尝试连接到组成ZooKeeper服务的服务器之一，此时它将切换为CONNECTED状态。在正常操作期间，客户端句柄将处于这两种状态之一。如果发生不可恢复的错误，例如会话到期或身份验证失败，或者如果应用程序显式关闭了句柄，则该句柄将移至CLOSED状态。
