# RocketMQ源码解析之消息存储
消息存储是 RocketMQ 中最为复杂和最为重要的一部分，包含 RocketMQ 的消息存储整体架构、PageCache 与 Mmap 内存映射以及 RocketMQ 中两种不同的刷盘方式三方面来分别展开叙述。
下边是一张RocketMQ官方的一张消息架构图：
![消息架构图](https://img-blog.csdnimg.cn/20200727102256153.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L2t3eHl6aw==,size_16,color_FFFFFF,t_70)

消息存储架构图中主要有下面三个跟消息存储相关的文件构成。

(1) CommitLog：消息主体以及元数据的存储主体，存储Producer端写入的消息主体内容,消息内容不是定长的。单个文件大小默认1G ，文件名长度为20位，左边补零，剩余为起始偏移量，比如00000000000000000000代表了第一个文件，起始偏移量为0，文件大小为1G=1073741824；当第一个文件写满了，第二个文件为00000000001073741824，起始偏移量为1073741824，以此类推。消息主要是顺序写入日志文件，当文件满了，写入下一个文件；

(2) ConsumeQueue：消息消费队列，引入的目的主要是提高消息消费的性能，由于RocketMQ是基于主题topic的订阅模式，消息消费是针对主题进行的，如果要遍历commitlog文件中根据topic检索消息是非常低效的。Consumer即可根据ConsumeQueue来查找待消费的消息。其中，ConsumeQueue（逻辑消费队列）作为消费消息的索引，保存了指定Topic下的队列消息在CommitLog中的起始物理偏移量offset，消息大小size和消息Tag的HashCode值。consumequeue文件可以看成是基于topic的commitlog索引文件，故consumequeue文件夹的组织方式如下：topic/queue/file三层组织结构，具体存储路径为：$HOME/store/consumequeue/{topic}/{queueId}/{fileName}。同样consumequeue文件采取定长设计，每一个条目共20个字节，分别为8字节的commitlog物理偏移量、4字节的消息长度、8字节tag hashcode，单个文件由30W个条目组成，可以像数组一样随机访问每一个条目，每个ConsumeQueue文件大小约5.72M；

(3) IndexFile：IndexFile（索引文件）提供了一种可以通过key或时间区间来查询消息的方法。Index文件的存储位置是：$HOME \store\index\${fileName}，文件名fileName是以创建时的时间戳命名的，固定的单个IndexFile文件大小约为400M，一个IndexFile可以保存 2000W个索引，IndexFile的底层存储设计为在文件系统中实现HashMap结构，故rocketmq的索引文件其底层实现为hash索引。

下边我们从源码上看一下RocketMQ是如何实现消息存储的
## MappedFile 文件内存映射
在 RocketMQ 中，使用 MappedFile 文件映射对象映射内存到磁盘的文件内容，在 MappedFile 中，使用 OS 的文件缓存——页缓存（pageCache）对文件的读写进行加速，利用 NIO 的FileChannel模型将磁盘上的物理文件直接映射到用户态的内存地中内存地址中，通过MappedByteBuffer对文件进行读写操作。  
ps:这种Mmap的方式减少了传统IO将磁盘文件数据在操作系统内核地址空间的缓冲区和用户应用程序地址空间的缓冲区之间来回进行拷贝的性能开销，将对文件的操作转化为直接对内存地址进行操作，从而极大地提高了文件的读写效率，正因为需要使用内存映射机制，故RocketMQ的文件存储都使用定长结构来存储，方便一次将整个文件映射至内存。  
下面我们看看MappedFile的主要实现：
```java
public class MappedFile extends ReferenceResource {
    public static final int OS_PAGE_SIZE = 1024 * 4;// OS 的文件缓存
    protected static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.STORE_LOGGER_NAME);
    private static final AtomicLong TOTAL_MAPPED_VIRTUAL_MEMORY = new AtomicLong(0);//内存中MappedFile文件使用的总内存
    private static final AtomicInteger TOTAL_MAPPED_FILES = new AtomicInteger(0);//内存中MappedFile文件的总数
    protected final AtomicInteger wrotePosition = new AtomicInteger(0);//写指针
    protected final AtomicInteger committedPosition = new AtomicInteger(0);//提交指针
    private final AtomicInteger flushedPosition = new AtomicInteger(0);//刷盘指针
    protected int fileSize;//当前MappedFile的文件大小
    protected FileChannel fileChannel;
    //消息先放入writeBuffer，然后如果writeBuffer为空的时候再重新放入FileChannel，这里使用TransientStorePool 的临时缓存writeBuffer池，
    //即先去池子里获取一个临时的writeBuffer，如果没有临时的则直接获取文件的FileChannel.mappedByteBuffer的writeBuffer
    protected ByteBuffer writeBuffer = null;
    protected TransientStorePool transientStorePool = null;//临时缓存writeBuffer池
    private String fileName;
    private long fileFromOffset;//文件的便宜地址——上面介绍的便宜文件名中获取偏移
    private File file;
    private MappedByteBuffer mappedByteBuffer;//FileChannel.mappedByteBuffer 读写操作
    private volatile long storeTimestamp = 0;//文件存储时间戳
    private boolean firstCreateInQueue = false;

    public MappedFile() {
    }

    public MappedFile(final String fileName, final int fileSize) throws IOException {
        init(fileName, fileSize);
    }

    public MappedFile(final String fileName, final int fileSize,
        final TransientStorePool transientStorePool) throws IOException {
        init(fileName, fileSize, transientStorePool);
    }
    private void init(final String fileName, final int fileSize) throws IOException {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.file = new File(fileName);
        this.fileFromOffset = Long.parseLong(this.file.getName());
        boolean ok = false;
        ensureDirOK(this.file.getParent());//确保文件存在——不存在则重新创建
        try {
            this.fileChannel = new RandomAccessFile(this.file, "rw").getChannel();
            this.mappedByteBuffer = this.fileChannel.map(MapMode.READ_WRITE, 0, fileSize);
            TOTAL_MAPPED_VIRTUAL_MEMORY.addAndGet(fileSize);//累计总文件大小
            TOTAL_MAPPED_FILES.incrementAndGet();//累计总文件数
            ok = true;
        } catch (FileNotFoundException e) {
            log.error("Failed to create file " + this.fileName, e);
            throw e;
        } catch (IOException e) {
            log.error("Failed to map file " + this.fileName, e);
            throw e;
        } finally {
            if (!ok && this.fileChannel != null) {
                this.fileChannel.close();
            }
        }
    }

    public static void ensureDirOK(final String dirName) {
        if (dirName != null) {
            File f = new File(dirName);
            if (!f.exists()) {
                boolean result = f.mkdirs();
                log.info(dirName + " mkdir " + (result ? "OK" : "Failed"));
            }
        }
    }
    //添加消息，最终通过委托CommitLog解析数据然后写入文件的byteBuffer，这里只要是返回文件的内存缓存byteBuffer供CommitLog使用
    public AppendMessageResult appendMessage(final MessageExtBrokerInner msg, final AppendMessageCallback cb) {
        return appendMessagesInner(msg, cb);
    }

    public AppendMessageResult appendMessages(final MessageExtBatch messageExtBatch, final AppendMessageCallback cb) {
        return appendMessagesInner(messageExtBatch, cb);
    }

    public AppendMessageResult appendMessagesInner(final MessageExt messageExt, final AppendMessageCallback cb) {
        assert messageExt != null;
        assert cb != null;

        int currentPos = this.wrotePosition.get();//获取写指针

        if (currentPos < this.fileSize) { //判断文件是否已满
            //判断使用临时ByteBuffer还是文件自己的ByteBuffer
            ByteBuffer byteBuffer = writeBuffer != null ? writeBuffer.slice() : this.mappedByteBuffer.slice();
            byteBuffer.position(currentPos);//指定写入位置
            AppendMessageResult result;
            //调用CommitLog将消息添写入writeBuffer
            if (messageExt instanceof MessageExtBrokerInner) {
                result = cb.doAppend(this.getFileFromOffset(), byteBuffer, this.fileSize - currentPos, (MessageExtBrokerInner) messageExt);
            } else if (messageExt instanceof MessageExtBatch) {
                result = cb.doAppend(this.getFileFromOffset(), byteBuffer, this.fileSize - currentPos, (MessageExtBatch) messageExt);
            } else {
                return new AppendMessageResult(AppendMessageStatus.UNKNOWN_ERROR);
            }
            this.wrotePosition.addAndGet(result.getWroteBytes());//移动写入指针
            this.storeTimestamp = result.getStoreTimestamp();//保存时间戳
            return result;
        }
        log.error("MappedFile.appendMessage return null, wrotePosition: {} fileSize: {}", currentPos, this.fileSize);
        return new AppendMessageResult(AppendMessageStatus.UNKNOWN_ERROR);
    }
    
    //在文件末尾将内容直接写入
    public boolean appendMessage(final byte[] data) {
        int currentPos = this.wrotePosition.get();

        if ((currentPos + data.length) <= this.fileSize) {
            try {
                this.fileChannel.position(currentPos);
                this.fileChannel.write(ByteBuffer.wrap(data));
            } catch (Throwable e) {
                log.error("Error occurred when append message to mappedFile.", e);
            }
            this.wrotePosition.addAndGet(data.length);
            return true;
        }

        return false;
    }

    //从offset开始写入data中length长度
    public boolean appendMessage(final byte[] data, final int offset, final int length) {
            int currentPos = this.wrotePosition.get();
    
            if ((currentPos + length) <= this.fileSize) {
                try {
                    this.fileChannel.position(currentPos);
                    this.fileChannel.write(ByteBuffer.wrap(data, offset, length));
                } catch (Throwable e) {
                    log.error("Error occurred when append message to mappedFile.", e);
                }
                this.wrotePosition.addAndGet(length);
                return true;
            }
    
            return false;
        }

    //提交内容，将缓存writeBuffer 的内容提交到文件fileChannel中，即将分配的写缓存通过指定的位置开始全部提交到文件缓存中，等待刷入磁盘
    public int commit(final int commitLeastPages) {
        if (writeBuffer == null) {
            //no need to commit data to file channel, so just regard wrotePosition as committedPosition.
            return this.wrotePosition.get();
        }
        if (this.isAbleToCommit(commitLeastPages)) {
            if (this.hold()) {
                commit0(commitLeastPages);
                this.release();
            } else {
                log.warn("in commit, hold failed, commit offset = " + this.committedPosition.get());
            }
        }

        // 所有产生的脏数据都提交写入-即当前文件存储已满，需要接入一个临时的缓存writeBuffer，将剩余的数据写入下一个文件中
        if (writeBuffer != null && this.transientStorePool != null && this.fileSize == this.committedPosition.get()) {
            this.transientStorePool.returnBuffer(writeBuffer);
            this.writeBuffer = null;
        }

        return this.committedPosition.get();
    }

    //提交数据
    protected void commit0(final int commitLeastPages) {
        int writePos = this.wrotePosition.get();
        int lastCommittedPosition = this.committedPosition.get();

        if (writePos - this.committedPosition.get() > 0) {
            try {
                ByteBuffer byteBuffer = writeBuffer.slice();
                byteBuffer.position(lastCommittedPosition);
                byteBuffer.limit(writePos);//设置提交长度
                this.fileChannel.position(lastCommittedPosition);
                this.fileChannel.write(byteBuffer);
                this.committedPosition.set(writePos);
            } catch (Throwable e) {
                log.error("Error occurred when commit data to FileChannel.", e);
            }
        }
    }

    //数据刷入磁盘
    public int flush(final int flushLeastPages) {
        if (this.isAbleToFlush(flushLeastPages)) {
            if (this.hold()) {
                int value = getReadPosition();

                try {
                    //我们数据是要么写入fileChannel 要么写入 mappedByteBuffer
                    if (writeBuffer != null || this.fileChannel.position() != 0) {
                        this.fileChannel.force(false);
                    } else {
                        this.mappedByteBuffer.force();
                    }
                } catch (Throwable e) {
                    log.error("Error occurred when force data to disk.", e);
                }

                this.flushedPosition.set(value);
                this.release();
            } else {
                log.warn("in flush, hold failed, flush offset = " + this.flushedPosition.get());
                this.flushedPosition.set(getReadPosition());
            }
        }
        return this.getFlushedPosition();
    }


    //通过指定的位置和大小读取文件的byteBuffer
    public SelectMappedBufferResult selectMappedBuffer(int pos, int size) {
        int readPosition = getReadPosition();
        if ((pos + size) <= readPosition) {//判断读的大小是否溢出
            if (this.hold()) {
                ByteBuffer byteBuffer = this.mappedByteBuffer.slice();
                byteBuffer.position(pos);
                ByteBuffer byteBufferNew = byteBuffer.slice();
                byteBufferNew.limit(size);
                return new SelectMappedBufferResult(this.fileFromOffset + pos, byteBufferNew, size, this);
            } else {
                log.warn("matched, but hold failed, request pos: " + pos + ", fileFromOffset: "
                    + this.fileFromOffset);
            }
        } else {
            log.warn("selectMappedBuffer request pos invalid, request pos: " + pos + ", size: " + size
                + ", fileFromOffset: " + this.fileFromOffset);
        }

        return null;
    }
    
    //读取byteBuffer指定位置到文件末尾的所有数据
    public SelectMappedBufferResult selectMappedBuffer(int pos) {
        int readPosition = getReadPosition();
        if (pos < readPosition && pos >= 0) {
            if (this.hold()) {
                ByteBuffer byteBuffer = this.mappedByteBuffer.slice();
                byteBuffer.position(pos);
                int size = readPosition - pos;
                ByteBuffer byteBufferNew = byteBuffer.slice();
                byteBufferNew.limit(size);
                return new SelectMappedBufferResult(this.fileFromOffset + pos, byteBufferNew, size, this);
            }
        }

        return null;
    }

```
上边是文件内存映射在程序正常运行的时候一些主要操作，但是内存中我们数据写入时可能需要很多的文件，这些文件的统一管理在 MappedFileQueue 中，这样我们在操作的时候就不需要知道数据具体在那个文件中，直接委托 MappedFileQueue 帮我们做，这样我们就可以把业务所需要的多个文件当成一个文件来操作(比如 CommitLog 可能需要多个物理文件存储，系统模型中一个服务只有一个 CommitLog)。我们通过 MappedFileQueue 来添加和获取 MappedFile 使用，在 MappedFileQueue 中我们提供了 MappedFile 各种搜索，删除和数据的提交，刷盘等操作 ：
```java
public class MappedFileQueue {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.STORE_LOGGER_NAME);
    private static final InternalLogger LOG_ERROR = InternalLoggerFactory.getLogger(LoggerName.STORE_ERROR_LOGGER_NAME);

    private static final int DELETE_FILES_BATCH_MAX = 10;

    private final String storePath;

    private final int mappedFileSize;

    private final CopyOnWriteArrayList<MappedFile> mappedFiles = new CopyOnWriteArrayList<MappedFile>();

    private final AllocateMappedFileService allocateMappedFileService;

    private long flushedWhere = 0;
    private long committedWhere = 0;

    private volatile long storeTimestamp = 0;

    public MappedFileQueue(final String storePath, int mappedFileSize,
        AllocateMappedFileService allocateMappedFileService) {
        this.storePath = storePath;
        this.mappedFileSize = mappedFileSize;
        this.allocateMappedFileService = allocateMappedFileService;
    }

    public MappedFile getMappedFileByTime(final long timestamp) {
        Object[] mfs = this.copyMappedFiles(0);

        if (null == mfs)
            return null;

        for (int i = 0; i < mfs.length; i++) {
            MappedFile mappedFile = (MappedFile) mfs[i];
            if (mappedFile.getLastModifiedTimestamp() >= timestamp) {
                return mappedFile;
            }
        }

        return (MappedFile) mfs[mfs.length - 1];
    }

    public MappedFile getLastMappedFile() {
            MappedFile mappedFileLast = null;
    
            while (!this.mappedFiles.isEmpty()) {
                try {
                    mappedFileLast = this.mappedFiles.get(this.mappedFiles.size() - 1);
                    break;
                } catch (IndexOutOfBoundsException e) {
                    //continue;
                } catch (Exception e) {
                    log.error("getLastMappedFile has exception.", e);
                    break;
                }
            }
    
            return mappedFileLast;
        }

    //通过位置获取该位置的数据在哪个内存映射文件中
    public MappedFile findMappedFileByOffset(final long offset, final boolean returnFirstOnNotFound) {
        try {
            MappedFile firstMappedFile = this.getFirstMappedFile();
            MappedFile lastMappedFile = this.getLastMappedFile();
            if (firstMappedFile != null && lastMappedFile != null) {
                if (offset < firstMappedFile.getFileFromOffset() || offset >= lastMappedFile.getFileFromOffset() + this.mappedFileSize) {
                    LOG_ERROR.warn("Offset not matched. Request offset: {}, firstOffset: {}, lastOffset: {}, mappedFileSize: {}, mappedFiles count: {}",
                        offset,
                        firstMappedFile.getFileFromOffset(),
                        lastMappedFile.getFileFromOffset() + this.mappedFileSize,
                        this.mappedFileSize,
                        this.mappedFiles.size());
                } else {
                    int index = (int) ((offset / this.mappedFileSize) - (firstMappedFile.getFileFromOffset() / this.mappedFileSize));
                    MappedFile targetFile = null;
                    try {
                        targetFile = this.mappedFiles.get(index);
                    } catch (Exception ignored) {
                    }

                    if (targetFile != null && offset >= targetFile.getFileFromOffset()
                        && offset < targetFile.getFileFromOffset() + this.mappedFileSize) {
                        return targetFile;
                    }

                    for (MappedFile tmpMappedFile : this.mappedFiles) {
                        if (offset >= tmpMappedFile.getFileFromOffset()
                            && offset < tmpMappedFile.getFileFromOffset() + this.mappedFileSize) {
                            return tmpMappedFile;
                        }
                    }
                }

                if (returnFirstOnNotFound) {
                    return firstMappedFile;
                }
            }
        } catch (Exception e) {
            log.error("findMappedFileByOffset Exception", e);
        }

        return null;
    }

    //未刷入磁盘的数据刷入磁盘
    public boolean flush(final int flushLeastPages) {
        boolean result = true;
        //通过上次记录的刷入指针找到需要执行刷入操作的MapperedFile
        MappedFile mappedFile = this.findMappedFileByOffset(this.flushedWhere, this.flushedWhere == 0);
        if (mappedFile != null) {
            long tmpTimeStamp = mappedFile.getStoreTimestamp();
            int offset = mappedFile.flush(flushLeastPages);
            long where = mappedFile.getFileFromOffset() + offset;
            result = where == this.flushedWhere;
            this.flushedWhere = where;
            if (0 == flushLeastPages) {
                this.storeTimestamp = tmpTimeStamp;
            }
        }

        return result;
    }

    //提交文件未提交的数据
    public boolean commit(final int commitLeastPages) {
        boolean result = true;
        //通过上次记录的提交指针找到需要执行刷入操作的MapperedFile
        MappedFile mappedFile = this.findMappedFileByOffset(this.committedWhere, this.committedWhere == 0);
        if (mappedFile != null) {
            int offset = mappedFile.commit(commitLeastPages);
            long where = mappedFile.getFileFromOffset() + offset;
            result = where == this.committedWhere;
            this.committedWhere = where;
        }

        return result;
    }
```
MappedFileQueue 主要是将很多的文件操作封装成一个队列对外提供操作，这样我们在上面介绍过的三个消息存储相关的文件当成单个文件，而数据在磁盘中的真实存储文件就通过 MappedFileQueue 来进行管理，上面我们介绍 MappedFileQueue 的几个常用方法，感兴趣的同学可以阅读源码来深入了解。

### CommitLog
存储所有元数据停机时间以进行恢复，确保数据保护的可靠性。CommitLog 里面主要有数据的存储和获取入口，下边我们介绍一个CommitLog 的提交和刷入磁盘：
```java
public class CommitLog {
    // 消息体 MAGIC CODE daa320a7
    public final static int MESSAGE_MAGIC_CODE = -626843481;
    protected static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.STORE_LOGGER_NAME);
    // 文件末尾填充 MAGIC CODE cbd43194
    protected final static int BLANK_MAGIC_CODE = -875286124;
    protected final MappedFileQueue mappedFileQueue;//文件
    protected final DefaultMessageStore defaultMessageStore;//store服务
    private final FlushCommitLogService flushCommitLogService;//刷盘线程服务
    private final FlushCommitLogService commitLogService;//提交线程服务
    private final AppendMessageCallback appendMessageCallback;//消息单元结构解析提交
    private final ThreadLocal<MessageExtBatchEncoder> batchEncoderThreadLocal;//消息编码服务
    protected HashMap<String/* topic-queueid */, Long/* offset */> topicQueueTable = new HashMap<String, Long>(1024);
    protected volatile long confirmOffset = -1L;
    private volatile long beginTimeInLock = 0;
    protected final PutMessageLock putMessageLock;//文件写入锁


    public PutMessageResult putMessage(final MessageExtBrokerInner msg) {
        //设置消息时间
        msg.setStoreTimestamp(System.currentTimeMillis());
        //消息内容crc32编码
        msg.setBodyCRC(UtilAll.crc32(msg.getBody()));
        AppendMessageResult result = null;
        StoreStatsService storeStatsService = this.defaultMessageStore.getStoreStatsService();//获取存储服务
        String topic = msg.getTopic();//获取消息topic
        int queueId = msg.getQueueId();
        final int tranType = MessageSysFlag.getTransactionValue(msg.getSysFlag());//获取消息事务类型
        if (tranType == MessageSysFlag.TRANSACTION_NOT_TYPE
            || tranType == MessageSysFlag.TRANSACTION_COMMIT_TYPE) {
            // 延迟级别
            if (msg.getDelayTimeLevel() > 0) {
                if (msg.getDelayTimeLevel() > this.defaultMessageStore.getScheduleMessageService().getMaxDelayLevel()) {
                    msg.setDelayTimeLevel(this.defaultMessageStore.getScheduleMessageService().getMaxDelayLevel());
                }

                topic = ScheduleMessageService.SCHEDULE_TOPIC;
                queueId = ScheduleMessageService.delayLevel2QueueId(msg.getDelayTimeLevel());

                // 备份真实的topic, queueId
                MessageAccessor.putProperty(msg, MessageConst.PROPERTY_REAL_TOPIC, msg.getTopic());
                MessageAccessor.putProperty(msg, MessageConst.PROPERTY_REAL_QUEUE_ID, String.valueOf(msg.getQueueId()));
                msg.setPropertiesString(MessageDecoder.messageProperties2String(msg.getProperties()));

                msg.setTopic(topic);
                msg.setQueueId(queueId);
            }
        }

        InetSocketAddress bornSocketAddress = (InetSocketAddress) msg.getBornHost();
        if (bornSocketAddress.getAddress() instanceof Inet6Address) {
            msg.setBornHostV6Flag();
        }
        InetSocketAddress storeSocketAddress = (InetSocketAddress) msg.getStoreHost();
        if (storeSocketAddress.getAddress() instanceof Inet6Address) {
            msg.setStoreHostAddressV6Flag();
        }
        long elapsedTimeInLock = 0;

        MappedFile unlockMappedFile = null;
        MappedFile mappedFile = this.mappedFileQueue.getLastMappedFile();//获取最后一个文件

        putMessageLock.lock(); //spin or ReentrantLock ,depending on store config
        try {
            long beginLockTimestamp = this.defaultMessageStore.getSystemClock().now();
            this.beginTimeInLock = beginLockTimestamp;

            msg.setStoreTimestamp(beginLockTimestamp);

            if (null == mappedFile || mappedFile.isFull()) {
                mappedFile = this.mappedFileQueue.getLastMappedFile(0); //如果当前文件已满，新建文件
            }
            if (null == mappedFile) {
                log.error("create mapped file1 error, topic: " + msg.getTopic() + " clientAddr: " + msg.getBornHostString());
                beginTimeInLock = 0;
                return new PutMessageResult(PutMessageStatus.CREATE_MAPEDFILE_FAILED, null);
            }
            //将文件提交到mappedFile中,通过appendMessageCallback进行消息单元存储结构的解析构造
            result = mappedFile.appendMessage(msg, this.appendMessageCallback);
            switch (result.getStatus()) {//处理结果判断
                case PUT_OK:
                    break;
                case END_OF_FILE:
                    unlockMappedFile = mappedFile;
                    // 如果文件没有写完数据，重新重建新文件接着写
                    mappedFile = this.mappedFileQueue.getLastMappedFile(0);
                    if (null == mappedFile) {
                        log.error("create mapped file2 error, topic: " + msg.getTopic() + " clientAddr: " + msg.getBornHostString());
                        beginTimeInLock = 0;
                        return new PutMessageResult(PutMessageStatus.CREATE_MAPEDFILE_FAILED, result);
                    }
                    result = mappedFile.appendMessage(msg, this.appendMessageCallback);
                    break;
                case MESSAGE_SIZE_EXCEEDED:
                case PROPERTIES_SIZE_EXCEEDED:
                    beginTimeInLock = 0;
                    return new PutMessageResult(PutMessageStatus.MESSAGE_ILLEGAL, result);
                case UNKNOWN_ERROR:
                    beginTimeInLock = 0;
                    return new PutMessageResult(PutMessageStatus.UNKNOWN_ERROR, result);
                default:
                    beginTimeInLock = 0;
                    return new PutMessageResult(PutMessageStatus.UNKNOWN_ERROR, result);
            }

            elapsedTimeInLock = this.defaultMessageStore.getSystemClock().now() - beginLockTimestamp;
            beginTimeInLock = 0;
        } finally {
            putMessageLock.unlock();
        }

        if (elapsedTimeInLock > 500) {
            log.warn("[NOTIFYME]putMessage in lock cost time(ms)={}, bodyLength={} AppendMessageResult={}", elapsedTimeInLock, msg.getBody().length, result);
        }

        if (null != unlockMappedFile && this.defaultMessageStore.getMessageStoreConfig().isWarmMapedFileEnable()) {
            this.defaultMessageStore.unlockMappedFile(unlockMappedFile);
        }

        PutMessageResult putMessageResult = new PutMessageResult(PutMessageStatus.PUT_OK, result);
        // 统计信息
        storeStatsService.getSinglePutMessageTopicTimesTotal(msg.getTopic()).incrementAndGet();
        storeStatsService.getSinglePutMessageTopicSizeTotal(topic).addAndGet(result.getWroteBytes());

        handleDiskFlush(result, putMessageResult, msg);//刷盘服务
        handleHA(result, putMessageResult, msg);//同步集群中的消息

        return putMessageResult;
    }

    //刷盘请求
    public CompletableFuture<PutMessageStatus> submitFlushRequest(AppendMessageResult result, PutMessageResult putMessageResult,
                                                                  MessageExt messageExt) {
        // 同步刷盘
        if (FlushDiskType.SYNC_FLUSH == this.defaultMessageStore.getMessageStoreConfig().getFlushDiskType()) {
            final GroupCommitService service = (GroupCommitService) this.flushCommitLogService;
            if (messageExt.isWaitStoreMsgOK()) {
                GroupCommitRequest request = new GroupCommitRequest(result.getWroteOffset() + result.getWroteBytes(),
                        this.defaultMessageStore.getMessageStoreConfig().getSyncFlushTimeout());
                service.putRequest(request);//提交消息
                return request.future();
            } else {
                service.wakeup();
                return CompletableFuture.completedFuture(PutMessageStatus.PUT_OK);
            }
        }
        // 异步刷盘
        else {
            if (!this.defaultMessageStore.getMessageStoreConfig().isTransientStorePoolEnable()) {
                flushCommitLogService.wakeup();//如果不是临时缓存刷入磁盘
            } else  {
                commitLogService.wakeup();//先将临时缓存提交到文件对应的缓存通道中
            }
            return CompletableFuture.completedFuture(PutMessageStatus.PUT_OK);
        }
    }
```
当 CommitLog 向 MappedFile 提交log消息是， MappedFile 通过回调 AppendMessageCallback 将log消息格式化后写入 MapperedFile的缓冲区内，下边我们看一下 CommitLog 的消息体结构：
长度  | 字段 | 描述
-------- | :------- | :------
4 |  TOTALSIZE | 消息长度
4 |  MAGICCODE | 魔数，是固定值，有MESSAGE_MAGIC_CODE和BLANK_MAGIC_CODE
4 |  BODYCRC | 消息体的校验码
4 |  QUEUEID | 消息MessageQueue
4 |  FLAG| flag是创建Message对象时由生产者通过构造器设定的flag值
8 |  QUEUEOFFSET| MessageQueue的位置
8 |  PHYSICALOFFSET| 消息在存储文件中的偏移量
4 |  SYSFLAG| 生产者相关的信息标识
8 |  BORNTIMESTAMP| 消息创建时间
8/20 |  BORNHOST| 消息生产者的host地址
8 |  STORETIMESTAMP| 消息存储时间
8/20 |  STOREHOSTADDRESS|  消息存储机器的host地址
4 |  RECONSUMETIMES| 重复消费次数
4 |  Prepared Transaction Offset| 消息事务相关偏移量
4 |  BODY | 消息体的长度
- |  TOTALSIZE | 消息休，不是固定长度
1 |  TOPIC | 消息topic的长度
- |  TOPIC BODY | 消息topic的内容
2 |  PROPERTIES| 消息properties的长度
- |  PROPERTIES|  消息properties的内容

消息格式化的代码实现如下：
```java
    public AppendMessageResult doAppend(final long fileFromOffset, final ByteBuffer byteBuffer, final int maxBlank,
            final MessageExtBrokerInner msgInner) {
            // STORETIMESTAMP + STOREHOSTADDRESS + OFFSET <br>

            // PHY OFFSET
            long wroteOffset = fileFromOffset + byteBuffer.position();

            int sysflag = msgInner.getSysFlag();

            int bornHostLength = (sysflag & MessageSysFlag.BORNHOST_V6_FLAG) == 0 ? 4 + 4 : 16 + 4;
            int storeHostLength = (sysflag & MessageSysFlag.STOREHOSTADDRESS_V6_FLAG) == 0 ? 4 + 4 : 16 + 4;
            ByteBuffer bornHostHolder = ByteBuffer.allocate(bornHostLength);
            ByteBuffer storeHostHolder = ByteBuffer.allocate(storeHostLength);

            this.resetByteBuffer(storeHostHolder, storeHostLength);
            String msgId;
            if ((sysflag & MessageSysFlag.STOREHOSTADDRESS_V6_FLAG) == 0) {
                msgId = MessageDecoder.createMessageId(this.msgIdMemory, msgInner.getStoreHostBytes(storeHostHolder), wroteOffset);
            } else {
                msgId = MessageDecoder.createMessageId(this.msgIdV6Memory, msgInner.getStoreHostBytes(storeHostHolder), wroteOffset);
            }

            // 记录 ConsumeQueue 信息
            keyBuilder.setLength(0);
            keyBuilder.append(msgInner.getTopic());
            keyBuilder.append('-');
            keyBuilder.append(msgInner.getQueueId());
            String key = keyBuilder.toString(); //message key 的构建
            Long queueOffset = CommitLog.this.topicQueueTable.get(key);
            if (null == queueOffset) {
                queueOffset = 0L;
                CommitLog.this.topicQueueTable.put(key, queueOffset); //保存key 和 ConsumeQueue 的位置映射
            }

            // 事务消息的处理
            final int tranType = MessageSysFlag.getTransactionValue(msgInner.getSysFlag());
            switch (tranType) {
                // Prepared and Rollback message is not consumed, will not enter the
                // consumer queuec
                case MessageSysFlag.TRANSACTION_PREPARED_TYPE:
                case MessageSysFlag.TRANSACTION_ROLLBACK_TYPE:
                    queueOffset = 0L;
                    break;
                case MessageSysFlag.TRANSACTION_NOT_TYPE:
                case MessageSysFlag.TRANSACTION_COMMIT_TYPE:
                default:
                    break;
            }

            /**
             * 消息格式化
             */
            final byte[] propertiesData =
                msgInner.getPropertiesString() == null ? null : msgInner.getPropertiesString().getBytes(MessageDecoder.CHARSET_UTF8);

            final int propertiesLength = propertiesData == null ? 0 : propertiesData.length;
            if (propertiesLength > Short.MAX_VALUE) {
                log.warn("putMessage message properties length too long. length={}", propertiesData.length);
                return new AppendMessageResult(AppendMessageStatus.PROPERTIES_SIZE_EXCEEDED);
            }
            final byte[] topicData = msgInner.getTopic().getBytes(MessageDecoder.CHARSET_UTF8);
            final int topicLength = topicData.length; //topic的长度

            final int bodyLength = msgInner.getBody() == null ? 0 : msgInner.getBody().length; //消息 body 的长度
             //计算消息长度   
            final int msgLen = calMsgLength(msgInner.getSysFlag(), bodyLength, topicLength, propertiesLength);

            // Exceeds the maximum message
            if (msgLen > this.maxMessageSize) {
                CommitLog.log.warn("message size exceeded, msg total size: " + msgLen + ", msg body size: " + bodyLength
                    + ", maxMessageSize: " + this.maxMessageSize);
                return new AppendMessageResult(AppendMessageStatus.MESSAGE_SIZE_EXCEEDED);
            }

            // 确定是否有足够的可用空间
            if ((msgLen + END_FILE_MIN_BLANK_LENGTH) > maxBlank) {
                //没有足够的空间，填充文件末尾BLANK_MAGIC_CODE
                this.resetByteBuffer(this.msgStoreItemMemory, maxBlank);
                // 1 TOTALSIZE
                this.msgStoreItemMemory.putInt(maxBlank);
                // 2 MAGICCODE
                this.msgStoreItemMemory.putInt(CommitLog.BLANK_MAGIC_CODE);
                // 3 The remaining space may be any value
                // Here the length of the specially set maxBlank
                final long beginTimeMills = CommitLog.this.defaultMessageStore.now();
                byteBuffer.put(this.msgStoreItemMemory.array(), 0, maxBlank);
                return new AppendMessageResult(AppendMessageStatus.END_OF_FILE, wroteOffset, maxBlank, msgId, msgInner.getStoreTimestamp(),
                    queueOffset, CommitLog.this.defaultMessageStore.now() - beginTimeMills);
            }

            // 初始化存储空间
            this.resetByteBuffer(msgStoreItemMemory, msgLen);
            // 1 TOTALSIZE
            this.msgStoreItemMemory.putInt(msgLen);
            // 2 MAGICCODE
            this.msgStoreItemMemory.putInt(CommitLog.MESSAGE_MAGIC_CODE);
            // 3 BODYCRC
            this.msgStoreItemMemory.putInt(msgInner.getBodyCRC());
            // 4 QUEUEID
            this.msgStoreItemMemory.putInt(msgInner.getQueueId());
            // 5 FLAG
            this.msgStoreItemMemory.putInt(msgInner.getFlag());
            // 6 QUEUEOFFSET
            this.msgStoreItemMemory.putLong(queueOffset);
            // 7 PHYSICALOFFSET
            this.msgStoreItemMemory.putLong(fileFromOffset + byteBuffer.position());
            // 8 SYSFLAG
            this.msgStoreItemMemory.putInt(msgInner.getSysFlag());
            // 9 BORNTIMESTAMP
            this.msgStoreItemMemory.putLong(msgInner.getBornTimestamp());
            // 10 BORNHOST
            this.resetByteBuffer(bornHostHolder, bornHostLength);
            this.msgStoreItemMemory.put(msgInner.getBornHostBytes(bornHostHolder));
            // 11 STORETIMESTAMP
            this.msgStoreItemMemory.putLong(msgInner.getStoreTimestamp());
            // 12 STOREHOSTADDRESS
            this.resetByteBuffer(storeHostHolder, storeHostLength);
            this.msgStoreItemMemory.put(msgInner.getStoreHostBytes(storeHostHolder));
            // 13 RECONSUMETIMES
            this.msgStoreItemMemory.putInt(msgInner.getReconsumeTimes());
            // 14 Prepared Transaction Offset
            this.msgStoreItemMemory.putLong(msgInner.getPreparedTransactionOffset());
            // 15 BODY
            this.msgStoreItemMemory.putInt(bodyLength);
            if (bodyLength > 0)
                this.msgStoreItemMemory.put(msgInner.getBody());
            // 16 TOPIC
            this.msgStoreItemMemory.put((byte) topicLength);
            this.msgStoreItemMemory.put(topicData);
            // 17 PROPERTIES
            this.msgStoreItemMemory.putShort((short) propertiesLength);
            if (propertiesLength > 0)
                this.msgStoreItemMemory.put(propertiesData);

            final long beginTimeMills = CommitLog.this.defaultMessageStore.now();
            // Write messages to the queue buffer
            byteBuffer.put(this.msgStoreItemMemory.array(), 0, msgLen);

            AppendMessageResult result = new AppendMessageResult(AppendMessageStatus.PUT_OK, wroteOffset, msgLen, msgId,
                msgInner.getStoreTimestamp(), queueOffset, CommitLog.this.defaultMessageStore.now() - beginTimeMills);

            switch (tranType) {
                case MessageSysFlag.TRANSACTION_PREPARED_TYPE:
                case MessageSysFlag.TRANSACTION_ROLLBACK_TYPE:
                    break;
                case MessageSysFlag.TRANSACTION_NOT_TYPE:
                case MessageSysFlag.TRANSACTION_COMMIT_TYPE:
                    // The next update ConsumeQueue information
                    CommitLog.this.topicQueueTable.put(key, ++queueOffset);
                    break;
                default:
                    break;
            }
            return result;
        }

    protected static int calMsgLength(int sysFlag, int bodyLength, int topicLength, int propertiesLength) {
        int bornhostLength = (sysFlag & MessageSysFlag.BORNHOST_V6_FLAG) == 0 ? 8 : 20;
        int storehostAddressLength = (sysFlag & MessageSysFlag.STOREHOSTADDRESS_V6_FLAG) == 0 ? 8 : 20;
        final int msgLen = 4 //TOTALSIZE
            + 4 //MAGICCODE
            + 4 //BODYCRC
            + 4 //QUEUEID
            + 4 //FLAG
            + 8 //QUEUEOFFSET
            + 8 //PHYSICALOFFSET
            + 4 //SYSFLAG
            + 8 //BORNTIMESTAMP
            + bornhostLength //BORNHOST
            + 8 //STORETIMESTAMP
            + storehostAddressLength //STOREHOSTADDRESS
            + 4 //RECONSUMETIMES
            + 8 //Prepared Transaction Offset
            + 4 + (bodyLength > 0 ? bodyLength : 0) //BODY
            + 1 + topicLength //TOPIC
            + 2 + (propertiesLength > 0 ? propertiesLength : 0) //propertiesLength
            + 0;
        return msgLen;
    }
```
## ConsumeQueue
消息消费队列，通过 ConsumeQueue 记录消费者的消费记录信息和控制消费者进行消费，我们看一下里面的存储结构主要有哪些内容，这里我们主要关注对象的储存结构，其他提供的方法阅读者可以自行去了解。
![在这里插入图片描述](https://img-blog.csdnimg.cn/20200727160916104.png)
```java
public class ConsumeQueue {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.STORE_LOGGER_NAME);
    public static final int CQ_STORE_UNIT_SIZE = 20;
    private static final InternalLogger LOG_ERROR = InternalLoggerFactory.getLogger(LoggerName.STORE_ERROR_LOGGER_NAME);
    private final DefaultMessageStore defaultMessageStore;//默认存储服务
    private final MappedFileQueue mappedFileQueue;//内存映射文件队列
    private final String topic;
    private final int queueId;
    private final ByteBuffer byteBufferIndex;
    private final String storePath; //存储目录
    private final int mappedFileSize; //ConsumeQueue文件数
    private long maxPhysicOffset = -1;
    private volatile long minLogicOffset = 0;
    private ConsumeQueueExt consumeQueueExt = null; //ConsumeQueue补充服务

    /**
     * ConsumeQueue 消息存储单元
     */
    public static class CqExtUnit {
        public static final short MIN_EXT_UNIT_SIZE
            = 2 * 1 // size, 32k max
            + 8 * 2 // msg time + tagCode
            + 2; // bitMapSize

        public static final int MAX_EXT_UNIT_SIZE = Short.MAX_VALUE;
        public CqExtUnit() {
        }

        public CqExtUnit(Long tagsCode, long msgStoreTime, byte[] filterBitMap) {
            this.tagsCode = tagsCode == null ? 0 : tagsCode;
            this.msgStoreTime = msgStoreTime;
            this.filterBitMap = filterBitMap;
            this.bitMapSize = (short) (filterBitMap == null ? 0 : filterBitMap.length);
            this.size = (short) (MIN_EXT_UNIT_SIZE + this.bitMapSize);
        }

        /**
         * 单元大小
         */
        private short size;
        /**
         * 8位的tagsCode
         */
        private long tagsCode;
        /**
         * 8位的存储时间
         */
        private long msgStoreTime;
        /**
         * 2位的bitMap长度
         */
        private short bitMapSize;
        /**
         * 过滤的bitMap —— 如我们消费者在消费上配置的tag 匹配表达式 （a*）
         */
        private byte[] filterBitMap;
    }
    
    
    public void putMessagePositionInfoWrapper(DispatchRequest request) {
        final int maxRetries = 30;
        boolean canWrite = this.defaultMessageStore.getRunningFlags().isCQWriteable();//判断是否可以写入
        for (int i = 0; i < maxRetries && canWrite; i++) {//重试 maxRetries = 30 
            long tagsCode = request.getTagsCode();
            if (isExtWriteEnable()) {
                ConsumeQueueExt.CqExtUnit cqExtUnit = new ConsumeQueueExt.CqExtUnit(); //构造消息体cqExtUnit
                cqExtUnit.setFilterBitMap(request.getBitMap());
                cqExtUnit.setMsgStoreTime(request.getStoreTimestamp());
                cqExtUnit.setTagsCode(request.getTagsCode());
                long extAddr = this.consumeQueueExt.put(cqExtUnit);//写入cqExtUnit
                if (isExtAddr(extAddr)) {
                    tagsCode = extAddr;
                } else {
                    log.warn("Save consume queue extend fail, So just save tagsCode! {}, topic:{}, queueId:{}, offset:{}", cqExtUnit,
                        topic, queueId, request.getCommitLogOffset());
                }
            }
            //写入消息位置信息
            boolean result = this.putMessagePositionInfo(request.getCommitLogOffset(),
                request.getMsgSize(), tagsCode, request.getConsumeQueueOffset());
            if (result) {
                if (this.defaultMessageStore.getMessageStoreConfig().getBrokerRole() == BrokerRole.SLAVE ||
                    this.defaultMessageStore.getMessageStoreConfig().isEnableDLegerCommitLog()) {
                    this.defaultMessageStore.getStoreCheckpoint().setPhysicMsgTimestamp(request.getStoreTimestamp());
                }
                this.defaultMessageStore.getStoreCheckpoint().setLogicsMsgTimestamp(request.getStoreTimestamp());
                return;
            } else {
                log.warn("[BUG]put commit log position info to " + topic + ":" + queueId + " " + request.getCommitLogOffset()
                    + " failed, retry " + i + " times");
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    log.warn("", e);
                }
            }
        }

        log.error("[BUG]consume queue can not write, {} {}", this.topic, this.queueId);
        this.defaultMessageStore.getRunningFlags().makeLogicsQueueError();
    }
    private boolean putMessagePositionInfo(final long offset, final int size, final long tagsCode,
        final long cqOffset) {
        if (offset + size <= this.maxPhysicOffset) {
            log.warn("Maybe try to build consume queue repeatedly maxPhysicOffset={} phyOffset={}", maxPhysicOffset, offset);
            return true;
        }
        //写入消息    
        this.byteBufferIndex.flip();
        this.byteBufferIndex.limit(CQ_STORE_UNIT_SIZE);
        this.byteBufferIndex.putLong(offset);
        this.byteBufferIndex.putInt(size);
        this.byteBufferIndex.putLong(tagsCode);
        final long expectLogicOffset = cqOffset * CQ_STORE_UNIT_SIZE;

        MappedFile mappedFile = this.mappedFileQueue.getLastMappedFile(expectLogicOffset);//获取mappedFile文件
        if (mappedFile != null) {
            if (mappedFile.isFirstCreateInQueue() && cqOffset != 0 && mappedFile.getWrotePosition() == 0) {
                this.minLogicOffset = expectLogicOffset;
                this.mappedFileQueue.setFlushedWhere(expectLogicOffset);//刷盘位置
                this.mappedFileQueue.setCommittedWhere(expectLogicOffset);//提交位置
                this.fillPreBlank(mappedFile, expectLogicOffset);
                log.info("fill pre blank space " + mappedFile.getFileName() + " " + expectLogicOffset + " "
                    + mappedFile.getWrotePosition());
            }
            if (cqOffset != 0) {
                long currentLogicOffset = mappedFile.getWrotePosition() + mappedFile.getFileFromOffset();
                if (expectLogicOffset < currentLogicOffset) {
                    log.warn("Build  consume queue repeatedly, expectLogicOffset: {} currentLogicOffset: {} Topic: {} QID: {} Diff: {}",
                        expectLogicOffset, currentLogicOffset, this.topic, this.queueId, expectLogicOffset - currentLogicOffset);
                    return true;
                }
                if (expectLogicOffset != currentLogicOffset) {
                    LOG_ERROR.warn(
                        "[BUG]logic queue order maybe wrong, expectLogicOffset: {} currentLogicOffset: {} Topic: {} QID: {} Diff: {}",
                        expectLogicOffset,
                        currentLogicOffset,
                        this.topic,
                        this.queueId,
                        expectLogicOffset - currentLogicOffset
                    );
                }
            }
            this.maxPhysicOffset = offset + size;
            return mappedFile.appendMessage(this.byteBufferIndex.array());//添加消息
        }
        return false;
    }
```

## IndexFile
索引文件：提供了一种可以通过key或时间区间来查询消息的方法，下边是官方的一张索引文件的具体结构图
![索引结构如](https://img-blog.csdnimg.cn/20200727142433217.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L2t3eHl6aw==,size_16,color_FFFFFF,t_70)
```java
public class IndexHeader {
    public static final int INDEX_HEADER_SIZE = 40;
    private static int beginTimestampIndex = 0;//8位长的开始时间
    private static int endTimestampIndex = 8;//8位长的结束时间
    private static int beginPhyoffsetIndex = 16;//8位长的物理偏移开始地址
    private static int endPhyoffsetIndex = 24;//8位长的物理偏移结束地址
    private static int hashSlotcountIndex = 32;//8位长的slot数量
    private static int indexCountIndex = 36;//8位长的index数量
    private final ByteBuffer byteBuffer;
    private AtomicLong beginTimestamp = new AtomicLong(0);
    private AtomicLong endTimestamp = new AtomicLong(0);
    private AtomicLong beginPhyOffset = new AtomicLong(0);
    private AtomicLong endPhyOffset = new AtomicLong(0);
    private AtomicInteger hashSlotCount = new AtomicInteger(0);
    private AtomicInteger indexCount = new AtomicInteger(1);

    public void load() {
            this.beginTimestamp.set(byteBuffer.getLong(beginTimestampIndex));
            this.endTimestamp.set(byteBuffer.getLong(endTimestampIndex));
            this.beginPhyOffset.set(byteBuffer.getLong(beginPhyoffsetIndex));
            this.endPhyOffset.set(byteBuffer.getLong(endPhyoffsetIndex));
            this.hashSlotCount.set(byteBuffer.getInt(hashSlotcountIndex));
            this.indexCount.set(byteBuffer.getInt(indexCountIndex));
            if (this.indexCount.get() <= 0) {
                this.indexCount.set(1);
            }
        }

    public void incIndexCount() {
        int value = this.indexCount.incrementAndGet();
        this.byteBuffer.putInt(indexCountIndex, value);
    }
    //……
}
```
上边就是 Index 的 Header 结构，下边我们看看 index 的结构
```java
public class IndexFile {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.STORE_LOGGER_NAME);
    private static int hashSlotSize = 4; //4位长的key hash
    private static int indexSize = 20; // 20位长的index 索引内容
    private static int invalidIndex = 0;
    private final int hashSlotNum; //key hash的数量
    private final int indexNum; // 索引的数量
    private final MappedFile mappedFile; //内存映射文件
    private final FileChannel fileChannel;
    private final MappedByteBuffer mappedByteBuffer;
    private final IndexHeader indexHeader;//索引头部

public boolean putKey(final String key, final long phyOffset, final long storeTimestamp) {
        if (this.indexHeader.getIndexCount() < this.indexNum) {
            int keyHash = indexKeyHashMethod(key);
            int slotPos = keyHash % this.hashSlotNum;
            int absSlotPos = IndexHeader.INDEX_HEADER_SIZE + slotPos * hashSlotSize;
            FileLock fileLock = null;
            try {
                // fileLock = this.fileChannel.lock(absSlotPos, hashSlotSize,false);
                int slotValue = this.mappedByteBuffer.getInt(absSlotPos);
                if (slotValue <= invalidIndex || slotValue > this.indexHeader.getIndexCount()) {
                    slotValue = invalidIndex;
                }
                long timeDiff = storeTimestamp - this.indexHeader.getBeginTimestamp();//时间间隔
                timeDiff = timeDiff / 1000;
                if (this.indexHeader.getBeginTimestamp() <= 0) {
                    timeDiff = 0;
                } else if (timeDiff > Integer.MAX_VALUE) {
                    timeDiff = Integer.MAX_VALUE;
                } else if (timeDiff < 0) {
                    timeDiff = 0;
                }
                //获取文件的存储指针（文件末尾） = 请求头的40+hashSlot的长度(个数*4)+索引index长度（个数*20）
                int absIndexPos =
                    IndexHeader.INDEX_HEADER_SIZE + this.hashSlotNum * hashSlotSize
                        + this.indexHeader.getIndexCount() * indexSize;

                this.mappedByteBuffer.putInt(absIndexPos, keyHash);//文件末尾写入4位长的keyHash
                this.mappedByteBuffer.putLong(absIndexPos + 4, phyOffset);//写入8位长的物理起始地址
                this.mappedByteBuffer.putInt(absIndexPos + 4 + 8, (int) timeDiff);//写入4位长的存储时间间隔
                this.mappedByteBuffer.putInt(absIndexPos + 4 + 8 + 4, slotValue);//写入4位长的slot
                this.mappedByteBuffer.putInt(absSlotPos, this.indexHeader.getIndexCount());//写入8位长的index数量

                if (this.indexHeader.getIndexCount() <= 1) {
                    this.indexHeader.setBeginPhyOffset(phyOffset);//请求头设置8位长的物理起始地址
                    this.indexHeader.setBeginTimestamp(storeTimestamp);//请求头设置8位长的存储开始时间
                }

                this.indexHeader.incHashSlotCount();//请求头增加slot个数
                this.indexHeader.incIndexCount();//请求头增加index个数
                this.indexHeader.setEndPhyOffset(phyOffset);//请求头设置8位长的终止物理地址
                this.indexHeader.setEndTimestamp(storeTimestamp);//请求头设置8位长的存储结束时间

                return true;
            } catch (Exception e) {
                log.error("putKey exception, Key: " + key + " KeyHashCode: " + key.hashCode(), e);
            } finally {
                if (fileLock != null) {
                    try {
                        fileLock.release();
                    } catch (IOException e) {
                        log.error("Failed to release the lock", e);
                    }
                }
            }
        } else {
            log.warn("Over index file capacity: index count = " + this.indexHeader.getIndexCount()
                + "; index max num = " + this.indexNum);
        }
        return false;
    }

    //……
}

```

这里我们主要介绍了RoctetMQ中有关消息存储结构的三个主要方面 CommitLog，ConsumeQueue 和 IndexFile。