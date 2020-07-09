# Redis基础知识

**Redis最为基于键值对的NoSQL数据库，具有高性能、丰富的数据结构、持久化、高可用和分布式等特性，同时Redis本身足够稳定，所以成为我们保证项目高并发、低迟延的一个重要的技术选择。**

Redis与很多的键值对数据库不同，Redis的值可以是string（字符串）、hash（哈希)、list（列表）、set（集合）、zset（有序集合）、BitMaps(位图)、HyperLog、GEO（地理信息定位）等多种数据结构和算法组成，因此Redis可以满足很多的应用场景。Redis选择将数据放入内存中，所以它的读写性能非常惊人，而且，Redis还将数据利用快照和日志的形式持久化到硬盘上，这样在发生断电或者机器故障时，能保证内存中的数据不会“丢失”，从而保障了它的高可用。

## Redis的数据类型

### 字符串

字符串类型是Redis最基础的数据结构。首先所有的键都是字符串类型的，而且其他几种数据类型都是在字符串的基础上构建的。字符串类型的值可以是字符串、数字，甚至是二进制，但最大值不能超过512MB。

**字符串的命令**
- 设置值  
  `set key value [ex seconds] [ps milliseconds] [ex|nx]`  
  `setex key seconds value `  
  `setnx key value`

  ex seconds:为键设置秒级过期  
  ps milliseconds： 为键设置毫秒级过期  
  ex: 键必须存在，才能设置成功，用于更新  
  nx：键必须不存在，才能设置成功，用于添加  

- 获取值  
  `get key`  
- 批量设置值  
  ` mset key value [key value key value ...]`  
- 批量获取值  
  `mget key [key ...]`

  *ps :学会使用批量操作，有助于提高业务处理效率，但是要注意的是每次批量操作所发送的命令数不是无节制的，如果数量过多可能造成Redis阻塞或者网络拥塞。*

**内部编码**
- int：8个字节的长整型。 
- embstr：小于等于39个字节的字符串。 
- raw：大于39个字节的字符串。  

*ps: Redis会根据当前值的类型和长度决定使用哪种内部编码实现。*

**典型使用场景**
1. 缓存功能
2. 计数
3. 共享Session
4. 限速  
...

#### 哈希
在Redis中，哈希类型是指键值本身又是一个键值对结构，形如value={{field1，value1}，...{fieldN，valueN}}  
*ps:哈希类型中的映射关系叫作field-value，注意这里的value是指field对应的值，不是键对应的值。*

**哈希的命令**
- 设置值  
`hset key field value`
- 获取值  (ps: 如果键或field不存在，会返回nil)
`hget key field` 
- 删除field  
`hdel key filed [field ...]`  
- 计算filed的个数  
`hlen key`
- 批量设置或获取field-value  
`hmget key filed [field ...]`  
`hset key field value [field value ..]`
- 判断field是否存在  
`hexists key field`
- 获取所有的field  
`hkeys key`
- 获取所有value    
`hvals key`
- 获取所有的field-value  *ps:在使用hgetall时，如果哈希元素个数比较多，会存在阻塞Redis的可能。*  
`hgetall key`
- 自增feild 的值  
`hincrby field`  
`hincrbyfloat field`

**内部编码**
1. ziplist（压缩列表）：当哈希类型元素个数小于hash-max-ziplist-entries 配置（默认512个）、同时所有值都小于hash-max-ziplist-value配置（默认64 字节）时，Redis会使用ziplist作为哈希的内部实现，ziplist使用更加紧凑的结构实现多个元素的连续存储，所以在节省内存方面比hashtable更加优秀。
2. hashtable（哈希表）：当哈希类型无法满足ziplist的条件时，Redis会使用hashtable作为哈希的内部实现，因为此时ziplist的读写效率会下降，而hashtable的读写时间复杂度为O（1）。

### 列表
列表（list）类型是用来存储多个有序的字符串。列表是一种比较灵活的数据结构，它可以充当栈和队列的角色，在实际开发上有很多应用场景。  
列表类型有两个特点：第一、列表中的元素是有序的，这就意味着可以通过索引下标获取某个元素或者某个范围内的元素列表；第二、列表中的元素可以是重复的。

**命令**
1. 添加
   - 从右边插入元素  
   `rpush key value [value ...]`  
   - 从左边插入元素  
   `lpush key value [value ...]`  
   - 向某个元素前或者后插入元素  
   `linsert key before|after pivot value`

2. 查找
    - 获取指定范围内的元素列表  
    `lrange key start end`  
    第一，索引下标从左到右分别是0到N-1，但是从右到左分别是-1到-N。 第二，lrange中的end选项包含了自身，
    - 获取列表指定索引下标的元素  
    `lindex key index`
    - 获取列表长度  
    `llen key`
 
 3. 删除
    - 从列表左侧弹出元素  
    `lpop key`
    - 从列表右侧弹出元素  
    `rpop key`
    - 删除指定元素  
    `lrem key count value`  
    count>0，从左到右，删除最多count个元素。  
    count<0，从右到左，删除最多count绝对值个元素。  
    count=0，删除所有。
    - 按照索引范围修剪列表  
    `ltrim key start`
4. 修改  
    修改指定索引下标的元素
    `lset key index newValue`
5. 阻塞操作
    - 阻塞式弹出  
    `blpop key [key ...] timeout`  
    `brpop key [key ...] timeout`  
    blpop和brpop是lpop和rpop的阻塞版本,key [key...]：多个列表的键,timeout：阻塞时间（单位：秒）。  
    *ps:列表为空：如果timeout=3，那么客户端要等到3秒后返回，如果 timeout=0，那么客户端一直阻塞等下去；列表不为空：客户端会立即返回*  
    **在使用brpop时，有两点需要注意。**
    1.如果是多个键，那么brpop会从左到右遍历键，一旦有一个键能弹出，则立刻向客户端返回。2.如果多个客户端执行同一个键的brpop，则最先执行命令的客户端可以获取到弹出的值。
    
**内部编码**
1. ziplist（压缩列表）：当列表的元素个数小于list-max-ziplist-entries配置 （默认512个），同时列表中每个元素的值都小于list-max-ziplist-value配置时 （默认64字节），Redis会选用ziplist来作为列表的内部实现来减少内存的使用。
2. linkedlist（链表）：当列表类型无法满足ziplist的条件时，Redis会使用 linkedlist作为列表的内部实现。
3. quicklist（跳跃表）：它是以一个ziplist为节点的linkedlist，它结合了ziplist和linkedlist两者的优势，为列表类型提供了一种更为优秀的内部编码实现。

###集合
集合（set）类型也是用来保存多个的字符串元素，但和列表类型不一 样的是，集合中不允许有重复元素，并且集合中的元素是无序的，不能通过索引下标获取元素。

**命令**
1. 集合内操作
    - 添加元素:返回结果为添加成功的元素个数  
    `sadd key element [element ...]`
    - 删除元素：返回结果为删除成功的元素个数  
    `srem key element [element ...]`
    - 计算元素的个数  
    `scard key`
    - 判断元素是否在集合中  
    `sismember key element`
    - 随机从集合中返回指定个数元素  
    `srandmenber key [count]` [count]是可选参数，如果不写默认为1
    - 从集合中随机弹出元素  
    `spop key`
    - 获取所有元素  
    `smembers key`
2. 集合间的操作
    - 求多个集合的交集  
    `sinter key [key ...]`
    - 求多个集合的并集  
    `sunion key [key ...]`
    - 求多个集合的差集  
    `sdiff key [key ...]`
    - 将交集、并集、差集的结果保存
    `sinterstore destination key [key ...]`  
    `sunionstore destination key [key ...]`  
    `sdiffstore destination key [key ...]`
    
**内部编码**
1. intset（整数集合）：当集合中的元素都是整数且元素个数小于set-max-intset-entries配置（默认512个）时，Redis会选择用intset来作为集合的内部实现，从而减少内存的使用。  
2. hashtable（哈希表）：当集合类型无法满足intset的条件时，Redis会使用hashtable作为集合的内部实现。

### 有序集合
有序集合保留了集合不能重复的特性，但有序集合中元素是可以排序的，与列表使用下标不同，Redis会为有序集合中的每个元素设置一个分数(score)作为排序依据。
*ps:有序集合中的元素不能重复，但是score可以重复，score的范围从负无穷到正无穷*

**命令**
1. 集合内
    - 添加成员  如果key不存在，则新建一个空的有序集合，如果key存在，但不是有序集合，则返回错误。
    `zadd [nx|xx] [ch] [incr] score member [score member ...]`  
    *ps: ·nx：member必须不存在，才可以设置成功，用于添加。 ·xx：member必须存在，才可以设置成功，用于更新。 ·ch：返回此次操作后，有序集合元素和分数发生变化的个数 ·incr：对score做增加，相当于后面介绍的zincrby。*
    - 计算成员个数  
    `zcard key`
    - 计算某个成员的分数  
    `zscore key member`
    - 计算成员排名  
    `zrank key member` 分数由低到高排名  
    `zrevrank key member` 分数由高到低排名
    - 删除成员  
    `zrem key member [member ...]`
    - 增加成员分数  
    `zincrby key increment menber` increment 增加的分数
    - 返回指定排名范围内的成员  
    `zrange key start end [withsciores]`  
    `zrevrange key start end [withscores]`
    - 返回指定分数范围内的成员  
    `zrangebyscore key min max [withscores] [limit offset count]`  
    `zrangebyscore key min max [withscores] [limit offset count]`  
    其中zrangebyscore按照分数从低到高返回，zrevrangebyscore反之。withscores选项会同时返回每个成员的分数。[limit offset count]选项可以限制输出的起始位置和个数，同时min和max还支持开区间（小括号）和闭区间（中括号），-inf和 +inf分别代表无限小和无限大
    - 删除指定排名内的升序成员  
    `zremrangebyrank key start end`
    - 删除指定分数范围内的成员  
    `zremrangescore key min max`
2. 集合间的操作
    - 交集  
    `zinterstore destination numkeys key [key ...] [weights weights [weight ...]] [aggregate sum|min|max]`  
    ·destination：交集计算结果保存到这个键。 ·numkeys：需要做交集计算键的个数。 ·key [key...]：需要做交集计算的键。·weights weight [weight...]：每个键的权重，在做交集计算时，每个键中的每个member会将自己分数乘以这个权重，每个键的权重默认是1。 ·aggregate sum|min|max：计算成员交集后，分值可以按照sum（和）、 min（最小值）、max（最大值）做汇总，默认值是sum。
    - 并集  
    `zunionstore destination numkeys key [key ...] [weights weight [weight ...] aggregate sum|min|max]`
    
 **内部编码**
1. ziplist（压缩列表）：当有序集合的元素个数小于zset-max-ziplist-entries配置（默认128个），同时每个元素的值都小于zset-max-ziplist-value配置（默认64字节）时，Redis使用ziplist作为有序集合的内部实现，较少内存的使用。
2. skiplist（跳跃表）：当ziplist条件不满足时，有序集合会使用skiplist作为内部实现，因为此时ziplist的读写效率会下降。

## 键管理
Redis从单个键、遍历键、数据库管理三个维度对键进行管理。
 ### 单个键管理
 针对单个键的命令
 1. 键重命名  *（ps：由于重命名键期间会执行del命令删除旧的键，如果键对应的值比较大，会存在阻塞Redis的可能性，这点不要忽视）*  
 `rename key newkey`  
 `renamenx key newkey` 只有新键名不存在时才能重命名
 2. 随机返回一个键  
 `randomkey`
 3. 键过期  *（`ttl/pttl key` 观察键过期时间[返回值大于等于0的整数：键剩余的过期时间（ttl是秒，pttl是毫秒）;-1：键没有设置过期时间;2：键不存在]）*  
 `expire key seconds` 键在secondes秒过期  
 `expireat key timestamp` 键在秒级时间戳timestamp后过期  
 `pexpire key milliseconds` 键在milliseconds毫秒后过期  
 `pexpireat key milliseconds-timestamp` 键在毫秒级时间戳timestamp后过期 *（ps:无论是使用过期时间还是时间戳，秒级还是毫秒级，在Redis内部最 终使用的都是pexpireat。）*  
  **在使用Redis相关过期命令时,需要注意几点**  
     - 如果expire key的键不存在，返回结果为0。
     - 如果过期时间为负值，键会立即被删除。
     - persist 命令可以将键过期时间清除。
     - 对于字符串类型键，执行set命令会去掉键的过期时间。
     - Redis不支持二级数据结构（哈希，列表等）内部元素的过期功能。
     - setex命令作为set+expire的组合，既是原子执行的，同时还较少了一次网络通信的时间。
 4. 迁移键  
    迁移键的功能就对我们非常重要，有时我们只是想把部分数据从一个Redis迁移到另一个Redis上或者Redis内部的一个db迁移到另一个db中，此时Redis提供我们move、dump+restore、migrate三组迁移键的方法。
    - move  
    `move key db`  
    move用于Redis内部迁移，Redis内部可以有多个数据库，彼此在数据上是隔离的，move key db就是从源数据库迁移到目标数据库中。  
    - dump+restore  
       `dump key`  
       `restore key ttl value`      
      dump+restore可以实现在不同的Redis实例之间进行数据迁移，整个过程分为两步：1：在源Redis上，dump命令会将键值序列化，格式采用RDB格式；2：在目标Redis上，restore命令会将上面序列化的值进行复原，其中ttl参数代表过期时间，如果ttl=0代表没有过期时间。同时需要注意两点：1.整个迁移过程是非原子性的，而是通过客户端分步完成的。2.迁移过程是开启了两个客户端连接，所以dump的结果不是直接在源Redis和目标Redis之间进行传输。
    - migrate  
     `migrate host port key|"" distination-db timeout [copy] [replace] [keys key [key ...]]`
     host：目标Redis的IP地址。  
     port：目标Redis的端口。  
     key|"" : ""是Redis3.0.6版本后的多键迁移  
     distination-db：目标Redis的数据库索引  
     timeout：迁移的超时时间  
     [copy]：如果添加此选项，迁移后并不删除源键  
     [replace]：migrate不管目标Redis是否存在键都会正常迁移进行数据覆盖  
     [keys key [key ...]] : 迁移多个键  
     migrate命令也是用于在Redis实例间进行数据迁移的，实际上migrate命令就是将dump、restore、del三个命令进行组合，从而简化了操作流程。
     migrate命令具有原子性，而且从Redis3.0.6版本以后已经支持迁移多个键的功能，有效地提高了迁移效率，migrate在10.4节水平扩容中起到重要作用。
 ### 遍历键
 Redis提供两个命令遍历所有的键，keys和scan(hkeys遍历哈希key的所有field)。
 1. 全量遍历键
     `keys pattern`  
     keys命令支持pattern匹配， keys * 获取所有的键。*代表匹配任意字符，?代表一个字符，[a,b]代表匹配部分字符,[a-b]代表匹配从a-b的字符，[^a] 非a字符，\ 用来转义。  
     *如果Redis包含了大量的键，执行keys命令很可能会造成Redis阻塞，所以一般建议不要在生产环境下使用keys命令。使用scan命令渐进式的遍历所有键，可以有效防止阻塞。*
 2. 渐进式遍历
    `scan cursor [match pattern] [count number]`  
    cursor: 游标从0开始，每次遍历都会返回当前游标的值，知道游标值为0，表示遍历结束。  
    match pattern：模式匹配  
    count number： 表示每次要遍历的键的个数，默认为10
    
    渐进式遍历可以有效的解决keys命令可能产生的阻塞问题，但是scan并非完美无瑕，如果在scan的过程中如果有键的变化（增加、删除、修改），那么遍历效果可能会碰到如下问题：新增的键可能没有遍历到，遍历出了重复的键等情况，也就是说scan并不能保证完整的遍历出来所有的键，这些是我们在开发时需要考虑的。

 ### 数据库管理
 Redis提供我们dbsize、select、flushdb/flushall命令供我们操作Redis数据库。
 1. 切换数据库  
    `select dbIndex`
    Redis提供我们默认16个数据库，Redis是单线程的,如果我们使用多个数据库，那么这些数据库仍然是使用一个CPU，彼此之间还会受到一定的影响，Redis3.0中已经逐渐弱化这个功能，Redis Cluster只允许使用0号数据库。
 2. flushdb/flushall  
    flushdb/flushall用于清除数据库，flushdb清除当前数据库，flushall清除所有数据库。如果当前数据库键值数量比较多，flushdb/flushall会存在阻塞Redis的可能性。
 3. dbsize  
    `dbsize` 返回当前数据库的 key 的数量
    
## Redis 中的一些很有用的小功能
  - 慢查询分析  
     Redis提供了slowlog-log-slower-than和slowlog-max-len配置来打印存储慢查询。  
     `slowlog get [n]` 获取n条慢查询  
     `showlog len`  获取慢查询的长度  
     `showlog reset` 重置慢查询  
     
  - Pipeline  
     它能将一组Redis命令进行组装，通过一次RTT传输给Redis，再将这组Redis命令的执行结果按顺序返回给客户端，Pipeline能节省我们对此执行命令的网络传输开销，极大的提高效率，和原生的批量相比，原生是原子的，pipeline是非原子的，由客服端和服务端共同实现，但相比原生的批量操作，Pipeline可以支持多个命令。
       
  - 事务与lua  
     为了保证多条命令组合的原子性，Redis提供了简单的事务功能以及集成Lua脚本来解决这个问题。
  
  - Bitmaps  
     实现位操作
  - GEO  
     Redis3.2版本提供了GEO（地理信息定位）功能，支持存储地理位置信息用来实现诸如附近位置。
     - 增加地理位置  
     `geoadd key longitude latitude member [longitude latitude member ...]`  
     - 获取地理位置  
     `geopos key member [member ...]`  
     - 获取两个地理位置的距离  
     `geodist key member1 member2 [unit]`  *unit m:米；km：公里；mi：英里；ft：英尺*  
     - 获取指定位置范围内的地理信息位置集合  
     `georadius key longitude latitude rediusm|km|ft|mi [withcoord] [withdist] [withhash] [COUNT count] [ase|desc] [store key] [storedist key]`  
     `georadiusbymember key member rediusm|km|ft|mi [withcoord] [withdist] [withhash] [COUNT count] [ase|desc] [store key] [storedist key]`  
     withcoord：返回结果中包含经纬度  
     withdist：返回结果钟过包含离中心节点位置的距离  
     withhash：返回结果中办好geohash  
     COUNT count：指定返回结果的数量  
     asc|desc：返回结果按照离中心节点的距离做升序或者降序  
     store key：将返回结果的地理位置信息保存到指定键  
     storedist key：将返回结果离中心节点的距离保存到指定键
     - 获取geohash  
     `geohash key member [member ...]`  
     Redis使用geohash将二维经纬度转换为一维字符串
     - 删除地理位置信息  
     `zrem key member`
 ## redis 的阻塞
   Redis是典型的单线程架构，所有的读写操作都是在一条主线程中完成。当Redis用于高并发场景时，如果出现阻塞，哪怕是很短时间，对于应用来说都是噩梦。  
   导致阻塞问题的场 景大致分为内在原因和外在原因：
   - 内部原因：不合理的使用API(keys、sort、hgetall、move等)或数据结构、CPU饱和、持久化阻塞等
   - 外在原因：CPU竞争、内存交换、网络问题等。
     
  客户端最先感知阻塞等Redis超时行为，加入日志监控报警工具可快速定位阻塞问题，同时需要对Redis进程和机器做全面监控。