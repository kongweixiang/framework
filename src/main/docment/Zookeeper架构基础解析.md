# Zookeeper源码解析之数据基础解析
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
      private long ctime; //znode节点被创建的纪元时间-毫秒级
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
Zookeeper 的节点根据生命周期和功能场景分为不同的类型：
```java
    public enum CreateMode {
    
        /**
         * 此类型znode客户端断开连接后不会自动删除
         */
        PERSISTENT(0, false, false, false, false),
        /**
         * 此类型znode客户端断开连接后不会自动删除，而且其名称将附加一个单调递增的数字
         */
        PERSISTENT_SEQUENTIAL(2, false, true, false, false),
        /**
         * 此类型znode为临时对象客户端断开连接后删除
         */
        EPHEMERAL(1, true, false, false, false),
        /**
         * 此类型znode为临时对象客户端断开连接后删除，而且其名称将附加一个单调递增的数字
         */
        EPHEMERAL_SEQUENTIAL(3, true, true, false, false),
        /**
         * 此类型znode为容器节点
         *容器节点是特殊用途的节点，可用于诸如领导者，锁等配方。
         *当删除容器的最后一个子代时，容器将成为服务器将来要删除的候选对象，此时如果设置子节点，将抛出NoNodeException
         */
        CONTAINER(4, false, false, true, false),
        /**
         * 此类型znode客户端断开连接后不会自动删除
         * 但如果在给定的TTL(过期时间)内没有修改，而且它本身没有子节点的情况下将被删除
         */
        PERSISTENT_WITH_TTL(5, false, false, false, true),
        /**
         * 此类型znode客户端断开连接后不会自动删除，而且其名称将附加一个单调递增的数字
         * 但如果在给定的TTL(过期时间)内没有修改，而且它本身没有子节点的情况下将被删除
         */
        PERSISTENT_SEQUENTIAL_WITH_TTL(6, false, true, false, true);
    
        private static final Logger LOG = LoggerFactory.getLogger(CreateMode.class);
    
        private boolean ephemeral;
        private boolean sequential;
        private final boolean isContainer;
        private int flag;
        private boolean isTTL;
    
        CreateMode(int flag, boolean ephemeral, boolean sequential, boolean isContainer, boolean isTTL) {
            this.flag = flag;
            this.ephemeral = ephemeral;
            this.sequential = sequential;
            this.isContainer = isContainer;
            this.isTTL = isTTL;
        }
    
       ……
    }
```
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

## ZooKeeper Sessions
ZooKeeper客户端通过使用语言绑定创建服务的句柄来与ZooKeeper服务建立会话。创建句柄后，该句柄将开始处于CONNECTING状态，并且客户端库尝试连接到组成ZooKeeper服务的服务器之一，此时它将切换为CONNECTED状态。在正常操作期间，客户端句柄将处于这两种状态之一。如果发生不可恢复的错误，例如会话到期或身份验证失败，或者如果应用程序显式关闭了句柄，则该句柄将移至CLOSED状态。
![在这里插入图片描述](https://img-blog.csdnimg.cn/2020072215250283.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L2t3eHl6aw==,size_16,color_FFFFFF,t_70)
ZooKeeper 中Session分为两类，本地会话和全局会话，ZooKeeperServer（独立）使用SessionTrackerImpl；LeaderZookeeper使用LeaderSessionTracker，它持有SessionTrackerImpl（全局）和LocalSessionTracker（如果启用）；FollowerZooKeeperServer和ObserverZooKeeperServer使用LearnerSessionTracker持有LocalSessionTracker。Zookeeper 中使用SessionTracker 管理和创建Session
```java
    public interface SessionTracker {
    
        //session 对象
        interface Session {
    
            long getSessionId();
            int getTimeout();
            boolean isClosing();
    
        }
        //session 过期
        interface SessionExpirer {
    
            void expire(Session session);
    
            long getServerId();
    
        }
        //新建session
        long createSession(int sessionTimeout);
    
        /**
         * 加入或追踪(超时检测)session，但不放入ZkDb中
         * @param id sessionId
         * @param to sessionTimeout
         * @return whether the session was newly tracked (if false, already tracked)
         */
        boolean trackSession(long id, int to);
    
        /**
         * session加入本地内存或者ZkDb中
         * @param id sessionId
         * @param to sessionTimeout
         * @return whether the session was newly added (if false, already existed)
         */
        boolean commitSession(long id, int to);
    
        /**
         * 检测sessionId 的session是否存在，存在则更新超时时间   
         * @param sessionId
         * @param sessionTimeout
         * @return false if session is no longer active
         */
        boolean touchSession(long sessionId, int sessionTimeout);
        
        ……
    }
```
![在这里插入图片描述](https://img-blog.csdnimg.cn/20200722180611698.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L2t3eHl6aw==,size_16,color_FFFFFF,t_70)
ZooKeeper的全局会话需要法定确认，开销会很大，所以引入本地会话，当localSessionsUpgradingEnabled开启时，LeaderZookeeper的本地会话可以自动升级为全局会话，本地会话不能创建临时节点，全局会话可以创建，但FollowerZooKeeperServer（追随者）和ObserverZooKeeperServer（观察者）为了避免创建临时节点和大量的会话，所以我们尽量将localSessionsUpgradingEnabled关闭。
                                 
## ZooKeeper Watches
  ZooKeeper 中，所有的读操作（getData(), getChildren(),和exists() ）都可以设置一个监听事件，在设置ZooKeeper监听需要考虑一下三点：
  - 一次性触发器： 数据更改后，一个监视事件将发送到客户端。例如，如果客户端执行getData（“ / znode1”，true），然后/ znode1的数据被更改或删除，则客户端将获得/ znode1的监视事件。如果/ znode1再次更改，则除非客户端进行了另一次读取来设置新的监视，否则不会发送任何监视事件。
  - 发送给客户端： 如果事件在发送到客户端的过程中或者因为网络延迟导致设置数据的操作已完成，但监听事件未到达客户端，ZooKeeper 会通过异步的保证机制，保证客户端顺序看到监听事件。
  - 监听可以设置在数据的哪些地方：ZooKeeper 有两个监听列表， 数据节点监听和数据子节点监听， getData() 和 exists() 设置监听在数据上，getChildren()设置监听在子节点上。
  
  下边是一下读操作时设置的监听事件和启用他们的事件调用：
   1. Created event: 调用在exists()设置的监听
   2. Deleted event: 调用在getData()，getChildren() 和 exists()设置的监听
   3. Changed event: 调用在getData() 和 exists()设置的监听
   4. Child event: getChildren() 设置的监听
 ```java
   enum EventType {
       None(-1),
       NodeCreated(1),
       NodeDeleted(2),
       NodeDataChanged(3),
       NodeChildrenChanged(4),
       DataWatchRemoved(5),
       ChildWatchRemoved(6),
       PersistentWatchRemoved (7);

       private final int intValue;     // Integer representation of value
       // for sending over wire

       EventType(int intValue) {
           this.intValue = intValue;
       }

       public int getIntValue() {
           return intValue;
       }

       public static EventType fromInt(int intValue) {
           switch (intValue) {
           case -1:
               return EventType.None;
           case 1:
               return EventType.NodeCreated;
           case 2:
               return EventType.NodeDeleted;
           case 3:
               return EventType.NodeDataChanged;
           case 4:
               return EventType.NodeChildrenChanged;
           case 5:
               return EventType.DataWatchRemoved;
           case 6:
               return EventType.ChildWatchRemoved;
           case 7:
               return EventType.PersistentWatchRemoved;

           default:
               throw new RuntimeException("Invalid integer value for conversion to EventType");
           }
       }
   }
```
  持久递归监听：3.6.0中的新增功能，从注册该监视的znode处递归地为所有znode递归触发
  
### ZooKeeper access control using ACLs
ZooKeeper 使用ACLs去控制它的访问权限，ACL实现与UNIX文件访问权限非常相似：它使用权限位来允许/禁止针对节点及其所应用范围的各种操作。与标准UNIX权限不同，ZooKeeper节点不受用户（文件所有者），组和环境（其他）的三个标准范围的限制。ZooKeeper没有znode所有者的概念。而是，ACL指定一组ID和与这些ID关联的权限。

ACL仅与特定的znode有关，不能递归，所以在父节点上设置的访问权限不影响子节点的访问权限，当客户端连接到ZooKeeper并对其进行身份验证时，ZooKeeper会将与该客户端相对应的所有ID与该客户端连接相关联。当客户端尝试访问节点时，将根据znodes的ACL检查这些ID。ACL由（scheme：expression，perms）对组成。表达式的格式特定于该方案。例如，该对（ip：19.22.0.0/16，READ）为IP地址以19.22开头的任何客户端提供READ权限。

ZooKeeper支持以下权限：
 - CREATE: 您可以创建一个子节点
 - READ: 您可以从节点获取数据并列出其子节点
 - WRITE: 您可以为节点设置数据
 - DELETE: 您可以删除一个子节点
 - ADMIN: 您可以设置权限
 
 ZooKeeper ACL 的数据接口
 ```java
    public class ACL implements Record {
      private int perms;
      private org.apache.zookeeper.data.Id id;
      public ACL() {
      }
      public ACL(
            int perms,
            org.apache.zookeeper.data.Id id) {
        this.perms=perms;
        this.id=id;
      }
    }

    public class Id implements Record {
      private String scheme;
      private String id;
      public Id() {
      }
      public Id(
            String scheme,
            String id) {
        this.scheme=scheme;
        this.id=id;
      }
    }
```
ACL 也可以通过字符串表示
```java
    public class AclParser {
    
        /**
         * ACL 列表字符串解析
         * @param aclString
         * @return
         */
        public static List<ACL> parse(String aclString) {
            List<ACL> acl;
            String[] acls = aclString.split(",");
            acl = new ArrayList<ACL>();
            for (String a : acls) {
                int firstColon = a.indexOf(':');
                int lastColon = a.lastIndexOf(':');
                if (firstColon == -1 || lastColon == -1 || firstColon == lastColon) {
                    System.err.println(a + " does not have the form scheme:id:perm");
                    continue;
                }
                ACL newAcl = new ACL();
                newAcl.setId(new Id(a.substring(0, firstColon), a.substring(firstColon + 1, lastColon)));
                newAcl.setPerms(getPermFromString(a.substring(lastColon + 1)));
                acl.add(newAcl);
            }
            return acl;
        }
    
        private static int getPermFromString(String permString) {
            int perm = 0;
            for (int i = 0; i < permString.length(); i++) {
                switch (permString.charAt(i)) {
                case 'r':
                    perm |= ZooDefs.Perms.READ;
                    break;
                case 'w':
                    perm |= ZooDefs.Perms.WRITE;
                    break;
                case 'c':
                    perm |= ZooDefs.Perms.CREATE;
                    break;
                case 'd':
                    perm |= ZooDefs.Perms.DELETE;
                    break;
                case 'a':
                    perm |= ZooDefs.Perms.ADMIN;
                    break;
                default:
                    System.err.println("Unknown perm type: " + permString.charAt(i));
                }
            }
            return perm;
        }
    }

    public interface Perms {
    
            int READ = 1 << 0;
    
            int WRITE = 1 << 1;
    
            int CREATE = 1 << 2;
    
            int DELETE = 1 << 3;
    
            int ADMIN = 1 << 4;
    
            int ALL = READ | WRITE | CREATE | DELETE | ADMIN;
    
        }
```
内置ACL方案
 - world：有一个id “anyone” 代表任何人。
 - auth： 是一种特殊的方案，它忽略任何提供的表达式，而是使用当前用户，凭据和方案。当持久化ACL时，ZooKeeper服务器将忽略提供的任何表达式（无论是像SASL身份验证那样的用户，还是像DIGEST身份验证这样的user：password）。但是，仍必须在ACL中提供该表达式，因为ACL必须与scheme：expression：perms格式匹配。提供此方案是为了方便，因为它是用户创建znode然后将对该znode的访问限制为仅该用户的常见用例。如果没有经过身份验证的用户，则使用身份验证方案设置ACL将失败。
 - digest：使用用户名：密码字符串生成MD5哈希，然后将其用作ACL ID身份。通过以明文形式发送username：password来完成认证。在ACL中使用时，表达式将是username：base64编码的SHA1密码摘要。
 - ip：使用客户端主机IP作为ACL ID身份。
 - x509：使用客户端X500主体作为ACL ID身份。
在访问znode，我们看一下在Zookeeper中ACL对象的验证：
```java
    //ZooKeeperServer.java
    public void checkACL(ServerCnxn cnxn, List<ACL> acl, int perm, List<Id> ids, String path, List<ACL> setAcls) throws KeeperException.NoAuthException {
            if (skipACL) {
                return;
            }
    
            LOG.debug("Permission requested: {} ", perm);
            LOG.debug("ACLs for node: {}", acl);
            LOG.debug("Client credentials: {}", ids);
    
            if (acl == null || acl.size() == 0) {
                return;
            }
            for (Id authId : ids) {
                if (authId.getScheme().equals("super")) {
                    return;
                }
            }
            for (ACL a : acl) {
                Id id = a.getId();
                if ((a.getPerms() & perm) != 0) {
                    if (id.getScheme().equals("world") && id.getId().equals("anyone")) {
                        return;
                    }
                    ServerAuthenticationProvider ap = ProviderRegistry.getServerProvider(id.getScheme());//获取内置ACL方案
                    if (ap != null) {
                        for (Id authId : ids) {
                            if (authId.getScheme().equals(id.getScheme())
                                && ap.matches(//通过内置ACL方案进行匹配
                                    new ServerAuthenticationProvider.ServerObjs(this, cnxn),
                                    new ServerAuthenticationProvider.MatchValues(path, authId.getId(), id.getId(), perm, setAcls))) {
                                return;
                            }
                        }
                    }
                }
            }
            throw new KeeperException.NoAuthException();
        }
     //ServerAuthenticationProvider.java
    public abstract boolean matches(ServerObjs serverObjs, MatchValues matchValues);
    public abstract boolean matches(ServerObjs serverObjs, MatchValues matchValues);
    //WrappedAuthenticationProvider.java
    public boolean matches(ServerObjs serverObjs, MatchValues matchValues) {
        return implementation.matches(matchValues.getId(), matchValues.getAclExpr());
    }

    public static class MatchValues {

        private final String path;
        private final String id;
        private final String aclExpr;
        private final int perm;
        private final List<ACL> setAcls;

        public MatchValues(String path, String id, String aclExpr, int perm, List<ACL> setAcls) {
            this.path = path;
            this.id = id;
            this.aclExpr = aclExpr;
            this.perm = perm;
            this.setAcls = setAcls;
        }
        //省略getter/setter
    }

```
Zookeeper 在`implementation.matches(matchValues.getId(), matchValues.getAclExpr())`中内置了上面介绍的ACL方案的实现。我们举两个简单的为例
```java
    //DigestAuthenticationProvider 摘要
    public boolean matches(String id, String aclExpr) {
        return id.equals(aclExpr);//通过id直接验证
    }    
    
    //IPAuthenticationProvider ip匹配
public boolean matches(String id, String aclExpr) {
        String[] parts = aclExpr.split("/", 2);
        byte[] aclAddr = addr2Bytes(parts[0]);
        if (aclAddr == null) {
            return false;
        }
        int bits = aclAddr.length * 8;
        if (parts.length == 2) {
            try {
                bits = Integer.parseInt(parts[1]);
                if (bits < 0 || bits > aclAddr.length * 8) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        mask(aclAddr, bits);
        byte[] remoteAddr = addr2Bytes(id);
        if (remoteAddr == null) {
            return false;
        }
        mask(remoteAddr, bits);
        for (int i = 0; i < remoteAddr.length; i++) {
            if (remoteAddr[i] != aclAddr[i]) {
                return false;
            }
        }
        return true;
    }
    private void mask(byte[] b, int bits) {
        int start = bits / 8;
        int startMask = (1 << (8 - (bits % 8))) - 1;
        startMask = ~startMask;
        while (start < b.length) {
            b[start] &= startMask;
            startMask = 0;
            start++;
        }
    }    
```                                               

  

