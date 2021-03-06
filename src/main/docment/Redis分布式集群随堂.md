#Redis分布式集群知识
随着应用数据越来越大和，对性能的要求越来越高，单机模式很难满足日渐多样化的需求，所以Redis也提供了分布式集群的部署方案，满足当下高并发，高可靠的需求。  
Redis是单线程的，集群不仅能更好的利用CPU的资源，还能提高对应用层的高可用，分布式集群还能避免单个Redis发生故障导致整个缓存的崩溃，引起应用的故障。  

##Redis集群的基础
Redis本身的一些设计，为Redis的集群打下了坚实的基础。
### Redis的持久化
Redis支持RDB和AOF两种持久化机制，持久化功能有效地避免因进程退出造成地数据丢失，当下次重启时利用之前持久化地文件即可实现数据地恢复。
#### RDB
RDB持久化是将当前进程数据生成快照保存到硬盘的过程，触发RDB持久化过程分为手动触发和自动触发。
1. 触发机制  
    手动触发分别对应save和bgsave命令。
    - save命令：阻塞当前Redis服务器，直到完成RDB过程为止，对于内存比较大的实例会造成长时间的阻塞，线上环境不建议使用。
    - bgsave命令：Redis进程执行fork操作创建子进程，RDB持久化由子进程负责，完成后自动结束。阻塞只发生在fork阶段，一般时间较短。
    
    bgsave命令明显是对save阻塞问题的优化，因此Redis内部所有的涉及RDB的操作都采用bgsave的方式，而save命令已经废弃。  
    Redis自动触发的一些场景：
    1. save相关配置，如“save m n”表示m秒内修改n次时自动触发bgsave。
    2. 如果从节点执行全量复制时，主节点自动执行bgsave生成RDB文件并发送给从节点。
    3. 执行bebug reload命令重新加载Redis时，也会自动触发save操作。
    4. 默认情况下执行shutdown命令时，如果没有开启AOF持久化功能则自动执行bgsave。
 
 2. 流程说明
     bgsave是主流的触发RDB持久化方式，大体的流程如下：
     ``` flow
    st=>inputoutput: bgsave
    op=>operation: 父进程
    cond=>condition: 是否有其他子进程正在执行?
    sub=>operation: 子进程
    rdb=>operation: 生成RDB
    notice=>operation: 通知父进程
    do=>operation: 执行其他命令
    e=>end: End
    
    st->op->cond
    cond(yes)->do->do
    cond(no)->sub->rdb->notice->e
     ```
    RDB文件保存在dir配置指定的目录下，文件名通过dbfilename配置指定。可以通过执行config set dir {newDir}和config set dbfilename {newFileName}运行期动态执行，当下次运行时RDB文件会保存到新目录。

 3. 优缺点
    - 优点：RDB是紧凑的二进制文件，代表Redis在某个时间上的快照，非常适用于备份、全量复制。Redis加载RDB恢复数据远远快于AOF方式。
    - 缺点：每次bgsave都会执行fork操作创建子进程，属于重量级操作，频繁操作成本过高，没法做到实时持久化/秒级持久化。
    
#### AOF
  开启AOF需要设置配置：appendonly yes （默认不开启），AOF文件名可以通过appendfilename配置设置（默认文件名是appendonly.aof）。  
  1. 触发机制  
      当开启AOF时，每次命令写入时都会进行AOF持久化。
  2. 流程说明  
       AOF工作流程分为四个操作：命令写入（append）、文件同步（sync）、文件重写（rewrite）和重启加载（load）
       ```flow
             append=>inputoutput: 命令写入
             sync=>operation: AOF缓冲
             aof=>operation: AOF文件
             load=>operation: 重启
             append->sync->sync->aof->load->
       ```
     1. AOF命令写入的内容直接是文本协议格式。
     2. AOF直接把命令追加到aof_buf中。
     3. 文件同步：系统调用write和fsync。write操作会触发延迟写；fsync对单个文件进行强制同步到硬盘。
     
     随着命令的不断写入AOF，文件会越来越大，为了解决这个问题，Redis引入了AOF重写机制压缩文件体积。AOF文件重写就是Redis把进程内的数据转化为写命令同步到新的AOF文件。重写后的AOF文件变小了，因为在重新过程中，抛弃了超时的数据，无效的命令；多条写命令合并成一个。  
     重写过程触发：手动触发（调用bgrewriteaof命令）和自动触发（根据auto-aof-rewrite-min-size(默认64MB)和auto-aof-rewrite-percentage参数确定自动触发时机）
  
  AOF会在fork操作和AOF追加到持久化时消耗大量性能造成阻塞，针对fork操作我们可以控制Redis实例的最大内存，使用对fork操作高效支持的机器等优化，AOF追加合理控制每次持久化数据的大小。
  
 ### Redis的复制
  Redis为我们提供了复制功能，实现了相同数据的多个Redis副本。  
  
  #### 配置
  参与复制的Redis实例划分为主节点（master）和从节点（slave）。默认情况下，Redis都是主节点。每个从节点只能有一个主节点，而主节点可以同时具有多个从节点。默认情况下，为了保证主从数据一致性，从节点使用slave-read-only=yes配置为只读模式，如果主节点设置requirepass参数进行密码验证，那么从节点的masterauth参数与主机点密码保持一致。  
  配置复制的方式有以下三种：  
  - 在配置文件中加入slaveof {masterHost} {masterPort}随Redis启动生效。
  - 在redis-server启动时加入--slaveof {masterHost} {masterPort}生效。
  - 直接使用命令slaveof {masterHost} {masterPort}生效。
 
 断开复制：在从节点执行slaveof no one，先断开与主节点的复制关系，再将从节点晋升为主节点。
 
 切换主节点：断开与旧主节点的复制关系；与新节点建立复制关系；删除从节点当前所有的数据；对新主节点进行复制操作。
  
 传输延迟：主从节点一般布置在不同的机器上，复制时网络延迟就成为需要考虑的问题，Redis通过参数repl-disable-tcp-nodelay用于控制是否关闭TCP_NODELAY，默认关闭。关闭时主机点产生的命令都会实时发送给子节点，主从延迟小，但增加了网络开销；开启时，主节点会合并较小的tcp数据包从而节省宽带，但增加了主从延迟，适用于主从网络环境复杂或宽带紧张的场景，如跨机房部署。
 #### 拓扑
 Redis的复制拓扑可以支持单层或者多层复制，根据拓扑复杂性可以分为一主一从，一主多从，树状主从三种。
  - 一主一从  
          ```mermaid
              graph TD
              A[Redis a] -->B[Redis b]
            ```
  - 一主多从
        ```mermaid
           graph TD
           A[Redis a] -->B[Redis b] 
           A --> C[Redis c]
           A --> d[Redis d]
         ```
  - 树状主从
        ```mermaid
             graph TD
             A[Redis a] -->B[Redis b] 
             A --> C[Redis c]
             B --> D[Redis d]
             B --> E[Redis e]
        ```
   
   #### 复制过程
   在从节点执行slaveof命令后，复制过程便开始运作，整个流程可以大致分为6个部分，如图所示：  
    ```flow
            st=>start: slave
            1=>operation: 保存主节点信息
            2=>operation: 主从建立socket通信
            3=>operation: 发送ping命令
            4=>operation: 权限验证
            5=>operation: 同步数据集
            6=>operation: 命令持续复制
            e=>end: master
            
            st->1->2->3->4->5->6->e->
    ```
  1. 保存主节点（master）信息:执行slave后从节点只保存主节点的地址信息便直接返回。
  2. 从节点（slave）内部通过每秒的定时任务维护复制相关的逻辑，当发现任务存在新的主节点后，会尝试与该节点建立网络连接。
  3. 发送ping命令，从节点通过ping检测主从网络套接字是否可用，检测主节点是否接受处理命令（如超时重连）。
  4. 权限验证：如果主节点设置了requirepass参数，则从节点通过masterauth参数与主节点进行密码验证。
  5. 同步数据集：主从第一次建立复制的场景，主节点会把所有的数据发送给从节点。
  6. 命令持续复制：当主节点把当前数据同步给从节点后，便完成了复制的建立流程，接下来主节点会持续地把写命令发送给从节点，从而保证主从数据一致性。
  
  **数据复制**
  - 全量复制：触发全量复制的命令是sync和psync。第一次主从复制时必须使用，这是从节点没有复制偏移量和主节点地运行ID。
  - 部分复制：使用psync {runId} {offset}命令实现，通过偏移量复制。
  - 异步复制： 主节点通过异步地方式把写命令发送给从节点。
  
  运维过程中我们应该尽量避免全量复制和过多从节点对主节点产生的复制风暴。
  
  
  **心跳**  
  主从节点在建立复制后，它们之间维护着长连接并彼此发送心跳命令。主节点通过ping命令判断从节点的存活性和连接状态；从节点通过上报自身的复制偏移量检查数据是否丢失，如果丢失则从主节点缓冲区拉取，较少从节点的数据延迟。
  
 ##Redis集群
  Redis目前已经提供我们两种方式进行集群部署：Redis Sentinel 和 Redis Cluster
  ### Redis Sentinel 
  Redis的主从复制模式下，一旦主节点由于故障不能提供服务，需要人工将从节点晋升为直接点，同时还要通知应用方主节点的变化，这种故障处理方式在很多应用场景下是无法接受的，所以Redis从2.8版本开始提供Redis Sentinel(哨兵)架构来解决这个问题。当主节点发生故障时，Redis Sentinel 能自动完成故障发现和故障转移，并通知业务方，从而实现真正的高可用。  
   *ps: Redis Sentinel的分布式是指：Redis数据节点、Sentinel节点集合、客户端分布在 多个物理节点的架构，跟Redis Cluster分布式不同，Redis Cluster是不仅服务分布式的，数据也是分布式的。Redis Sentinel通过自动完成故障发现和故障转移，实现高可用。注意分清两者的区别。*
  #### 部署
  部署拓扑如下：
      ```mermaid
      graph TD
      subgraph sentinel group 相互监控
      SE1[sentinel 26379]
      SE2[sentinel 26380]
      SE3[sentinel 26381]
      end
      A[master]
      B[slave 1]
      C[slave  2]
      A--复制-->B
      SE2--监控-->A
      SE2--监控-->B
      SE2--监控-->C
      A--复制-->C
      ```
      
 1. Redis Sentinel中Redis数据节点没有做任何特殊配，直接启动主节点和从节点，并确认主从关系。
 2. 部署多个Sentinel配置相同（这里端口不同），直接使用命令启动`redis-sentinel redis-sentinel-26379.conf` 或 `redis-server redis-sentinel-26379.conf --sentinel`,Sentinel本质上是一种特殊的Redis节点。当Sentinel启动后，发现主节点master，发现了它的两个从节点，同时发现Redis Sentinel一共有三个节点(Redis Sentinel能彼此感知，也能感知到Redis节点)。
  
 #### 实现原理
 1. 三个定时任务监控
    - 每隔10秒，每个Sentinel节点会向主节点和从节点发送info命令获取最新的拓扑结构。
    - 每隔2秒，每个Sentinel节点会向Redis数据节点的__sentinel__（了解其他 Sentinel节点以及它们对主节点的判断）。
    - 每隔1秒，每个Sentinel节点会向主节点、从节点、其余Sentinel节点发送一条ping命令做一次心跳检测，来确认这些节点当前是否可达。
 2. 主观下线和客观下线  
    每个Sentinel节点会每隔1秒对主节点、从节点、其他Sentinel节点发送ping命令做心跳检测，当这些节点超过`down-after-milliseconds`没有进行有效回复，Sentinel节点就会对该节点做失败判定，这个行为叫做主观下线。  
    当Sentinel主观下线的节点是主节点时，该Sentinel节点会通过`sentinel is-master-down-by-addr`命令向其他Sentinel节点询问对主节点的判断，当超过 `<quorum>`个数，Sentinel节点认为主节点确实有问题，这时该Sentinel节点会做出客观下线的决定。
 3. 领导者Sentinel节点选举  
 当Sentinel节点对于主节点做出客观下线后，Sentinel节点之间选出一个Sentinel节点作为领导者进行故障转移的工作。Redis Sentinel的选举思路大致如下：
    1. 每个在线的Sentinel节点都有资格成为领导者，当它确认主节点主管下线的时候，会向其他Sentinel节点发送`sentinel is-master-down-by-addr`命令，要求将自己设为领导者。
    2. 收到命令的Sentinel节点，如果没有同意过其他Sentinel节点的`sentinel is-master-down-by-addr`命令，将同意该请求，否则拒绝。
    3. 如果该Sentinel节点发现自己的票数已经大于等于max（quorum， num（sentinels）/2+1），那么它将成为领导者。
    4. 如果此过程没有选举出领导者，将进入下一次选举。
 
 4. 故障转移  
 领导者选举出的Sentinel节点负责故障转移，具体步骤如下：  
    1. 在从节点列表中选出一个节点作为新的主节点。选择方法如下:  
        a. 过滤：“不健康”（主观下线、断线）、5秒内没有回复过Sentinel节点ping响应、与主节点失联超过down-after-milliseconds*10秒。  
        b. 选择slave-priority（从节点优先级）最高的从节点列表，如果存在则返回，不存在则继续。  
        c. 选择复制偏移量最大的从节点（复制的最完整），如果存在则返回，不存在则继续。  
        d. 选择runid最小的节点。
    2. Sentinel领导节点会向选出的从节点发送slave no one命令让其称为主节点。
    3. Sentinel领导节点会向其余的从节点发送命令，让它们成为新主节点的从节点。
    4. Sentinel领导节会将原来的主节点更新为从节点，并保持对其关注，当其恢复后命令它去复制新的主节点。
    
 Sentinel节点集合具备了监控、通知、自动故障转移、配置提供者若干功能，最为了解Redis节点信息，所有客户端直接连接Redis Sentinel实现对Redis的访问。一个Redis Sentinel客户端基本上要实现：
  1. 遍历Sentinel节点集合获取一个可用的Sentinel节点。
  2. 通过`sentinel get-master-addr-by-name master-name`这个API来获取对应主节点的相关信息。
  3. 验证当前获取的“主节点”是真正的主节点，这样做的目的是为了防止故障转移期间主节点的变化。
  4. 保持和Sentinel节点集合的“联系”，时刻获取关于主节点的相关“信息”。
  
  *ps :Java操作Redis Sentinel的客户端Jedis*
  
 ### Redis Cluster
 Redis Cluster是Redis的分布式解决方案，在3.0版本正式推出，有效地解决了Redis分布式方面的需求。当遇到单机内存、并发、流量等瓶颈时，可以采用Cluster架构方案达到负载均衡的目的。  
 分布式数据库首先要解决把整个数据集按照分区规则映射到多个节点的问题，即把数据集划分到多个节点上，每个节点负责整体数据的一个子集。Redis Cluster的分布式也是采用这样的理论，且Redis Cluster采用哈希分区规则——虚拟槽分区，所有的键根据哈希函数映射到0~16383整数槽内，计算公式：slot=CRC16（key）&16383。每一个节点负责维护一部分槽以及槽所映射的键值数据。  
 Redis集群相对单机在功能上存在一些限制：
  - key批量操作支持有限。
  - key事务操作支持有限。
  - key作为数据分区的最小粒度，因此不能将一个大的键值对象如hash、list等映射到不同的节点。
  - 不支持多数据库空间。集群模式下只能使用一个数据库空间，即db0。
  - 复制结构只支持一层，从节点只能复制主节点，不支持嵌套树状复制结构。
  
  #### 搭建集群
  Redis Cluster集群搭建分为三个步骤：
  1. 准备节点  
    Redis集群一般由多个节点组成，节点数量至少为6个才能保证组成完整高可用的集群。每个节点需要开启配置`cluster-enabled yes`，让Redis运行在集群模式下。集群模式的Redis除了原有的配置文件之外又加了一份集群配置文件。当集群内节点信息发生变化，如添加节点、节点下线、故障转移等。节点会自动保存集群状态到配置文件中。
  2. 节点握手  
    节点握手是指一批运行在集群模式下的节点通过Gossip协议彼此通信， 达到感知对方的过程。节点握手是集群彼此通信的第一步，由客户端发起命令：cluster meet {ip} {port}。我们只需要在集群内任意节点上执行cluster meet命令加入新节点，握手状态会通过消息在集群内传播，这样其他节点会自动发现新节点并发起握手流程。节点建立握手之后集群还不能正常工作，这时集群处于下线状态，所有的数据读写都被禁止。只有当16384个槽全部分配给节点后，集群才进入在线状态
  3. 分配槽  
     Redis Cluster集群把所有的数据映射到16384个槽中。每个key会映射为一个固定的槽，只有当节点分配了槽，才能响应和这些槽关联的键命令。通过`cluster addslots`命令为节点分配槽。Reids节点角色分为主节点和从节点。首次启动的节点和被分配槽的节点都是主节点，从节点负责复制主节点槽信息和相关的数据。使用`cluster replicate {nodeId}`命令让一个节点成为从节点。
     
  我们可以使用redis-trib.rb帮助我们搭建集群：redis-trib.rb是采用Ruby实现的Redis集群管理工具。内部通过Cluster相关命令帮我们简化集群创建、检查、槽迁移和均衡等常见运维操作，使用之前需要安装Ruby依赖环境。
  
  #### 集群实现
  Redis Cluster集群包含集群伸缩、请求路由和故障转移三个方面。Redis集群数据分区规则采用虚拟槽方式，所有的键映射到16384个槽中，每个节点负责一部分槽和相关数据，实现数据和请求的负载均衡。
  ##### 集群伸缩
  Redis集群提供了灵活的节点扩容和收缩方案。在不影响集群对外服务的情况下，可以为集群添加节点进行扩容也可以下线部分节点进行缩容。  
  集群扩容：  
  1. 准备新节点  
    需要提前准备好新节点并运行在集群模式下，新节点建议跟集群内的其他节点配置保持一致，便于管理统一。  
  2. 加入集群  
    新节点依然采用cluster meet命令加入到现有集群中。新节点要么为迁移槽和数据实现扩容，要么作为其他主节点负责故障转移。  
  3. 迁移槽和数据  
  加入集群后需要为新节点迁移槽和相关数据，槽在迁移过程中集群可以正常提供读写服务。 
  槽是Redis集群管理数据的基本单位。首先需要为新节点制定槽的迁移计划，确定原有节点的哪些槽需要迁移到新节点。迁移计划需要确保每个节点负责相似数量的槽，从而保证各节点的数据均匀。数据迁移过程是逐个槽进行的，每个槽数据迁移的流程，迁移过程如下：  
     1. 对目标节点发送`cluster setslot {slot} importing {sourceNodeId}`命令，让目标节点准备导入槽的数据。
     2. 对源节点发送`cluster setslot {slot} migrating {targetNodeId}`命令，让源节点准备迁出槽的数据。
     3. 源节点循环执行`cluster getkeysinslot {slot} {count}`命令，获取count个属于槽{slot}的键。
     4. 在源节点上执行`migrate {targetIp} {targetPort}"" 0 {timeout} keys {key ...} 命令，把获取的键通过流水线（pipeline）机制批量迁移到目标节点，批量迁移版本的migrate命令在Redis3.0.6以上版本提供，之前的migrate命令只能单个键迁移。
     5. 重复执行步骤3和步骤4直到槽下所有的键值数据迁移到目标节点。
     6. 向集群内所有主节点发送`cluster setslot {slot} node {targetNodeId}`命令，通知槽分配给目标节点。
  
  *ps:使用redis-trib.rb可以快速帮助我们完成集群收缩*
  
  集群收缩：  
    收缩集群意味着缩减规模，需要从现有集群中安全下线部分节点。  
     1. 首先需要确定下线节点是否有负责的槽，如果是，需要把槽迁移到其他节点，保证节点下线后整个集群槽节点映射的完整性。收缩正好和扩容迁移方向相反。  
     2. 当下线节点不再负责槽或者本身是从节点时，就可以通知集群内其他节点忘记下线节点，当所有的节点忘记该节点后可以正常关闭。Redis提供了`cluster forget {downNodeId}`命令实现该功能。
     
  熟练掌握集群伸缩技巧后，可以针对线上的数据规模和并发量做到从容应对。   
  
  ##### 请求路由
  客户端去通过请求路由去操作集群。  
  1. 请求重定向： 在集群模式下，Redis接收任何键相关命令时首先计算键对应的槽，再根据槽找出所对应的节点，如果节点是自身，则处理键命令；否则回复MOVED重定向错误，通知客户端请求正确的节点。这个过程称为MOVED重定向。
  2. Smart客户端： Smart客户端通过在内部维护slot→node的映射关系，本地就可实现键到节点的查找，从而保证IO效率的最大化，而MOVED重定向负责协助Smart客户端更新slot→node映射。(java 的 JedisCluster)。
  3. ASK重定向：Redis集群支持在线迁移槽（slot）和数据来完成水平伸缩，当slot对应的数据从源节点到目标节点迁移过程中，客户端需要做到智能识别，保证键命令可正常执行。例如当一个slot数据从源节点迁移到目标节点时，期间可能出现一部分数据在源节点，而另一部分在目标节点。客户端根据本地slots缓存发送命令到源节点，如果键不存在则回复ASK重定向异常，客户端从ASK重定向异常提取出目标节点信息，发送asking命令到目标节点打开客户端连接标识，再执行键命令。
  
 *ps :集群环境下对于使用批量操作的场景，建议优先使用Pipeline方式，在客户端实现对ASK重定向的正确处理，这样既可以受益于批量操作的IO优化，又可以兼容slot迁移场景。*
##### 故障转移
 Redis集群自身实现了高可用。高可用首先需要解决集群部分失败的场景：当集群内少量节点出现故障时通过自动故障转移保证集群可以正常对外提供服务。  
 故障发现：  
 Redis集群内节点通过ping/pong消息实现节点通信，消息不但可以传播节点槽信息，还可以传播其他状态如：主从状态、节点故障等。故障发现也是通过消息传播机制实现的,主要环节包括：主观下线（pfail）和客观下线（fail）。
  - 主观下线：指某个节点认为另一个节点不可用（节点a内的定时任务检测到与节点b最后通信时间超高cluster-node-timeout时，更新本地对节点b的状态为主观下线（pfail）），即下线状态，注意，这个状态并不是最终的故障判定，只能代表一个节点的意见，可能存在误判情况。
  - 客观下线：指标记一个节点真正的下线，集群内多个节点都认为该节点不可用，从而达成共识的结果。如果是持有槽的主节点故障，需要为该节点进行故障转移。  
  当某个节点判断另一个节点主观下线后，相应的节点状态会跟随消息在集群内传播。通过Gossip消息传播，集群内节点不断收集到故障节点的下线报告。当半数以上持有槽的主节点都标记某个节点是主观下线时，向集群广播一条fail消息，通知所有的节点将故障节点标记为客观下线，触发客观下线流程。
  
  故障恢复：  
  故障节点变为客观下线后，如果下线节点是持有槽的主节点则需要在它的从节点中选出一个替换它，从而保证集群的高可用。流程如下：
  1. 资质检查：从节点与主节点断线时间超过cluster-node-time*cluster-slave-validity-factor，则当前从节点不具备故障转移资格。
  2. 准备选举时间：当从节点符合故障转移资格后，更新触发故障选举的时间，只有到达该时间后才能执行后续流程。（延迟不同的选举时间来支持优先级问题）
  3. 发起选举：  
        1. 更新配置纪元
        2. 广播选举消息
  4. 选举投票：只有持有槽的主节点才会处理故障选举消息 （FAILOVER_AUTH_REQUEST），因为每个持有槽的节点在一个配置纪元内都有唯一的一张选票，当接到第一个请求投票的从节点消息时回复 FAILOVER_AUTH_ACK消息作为投票，之后相同配置纪元内其他从节点的选举消息将忽略。投票过程其实是一个领导者选举的过程，如集群内有N个持有槽的主节点代表有N张选票。由于在每个配置纪元内持有槽的主节点只能投票给一个从节点，因此只能有一个从节点获得N/2+1的选票，保证能够找出唯一的从节点。  
  投票作废：每个配置纪元代表了一次选举周期，如果在开始投票之后的 cluster-node-timeout*2时间内从节点没有获取足够数量的投票，则本次选举作废。从节点对配置纪元自增并发起下一轮投票，直到选举成功为止。
  5. 替换主节点：当从节点收集到足够的选票之后，触发替换主节点操作：
        1. 当前从节点取消复制变为主节点。
        2. 执行clusterDelSlot操作撤销故障主节点负责的槽，并执行clusterAddSlot把这些槽委派给自己。
        3. 向集群广播自己的pong消息，通知集群内所有的节点当前从节点变为主节点并接管了故障主节点的槽信息。
  
  为了保证集群完整性，默认情况下当集群16384个槽任何一个没有指派到节点时整个集群不可用。这是对集群完整性的一种保护措施，保证所有的槽都指派给在线的节点。但是当持有槽的主节点下线时，从故障发现到自动完成转移期间整个集群是不可用状态，这样是很多应用服务不可接受的，所以将参数cluster-require-full-coverage配置为no，当主节点故障时只影响它负责槽的相关命令执行，不会影响其他主节点的可用性。
        