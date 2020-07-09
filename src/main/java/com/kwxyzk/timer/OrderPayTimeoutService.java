/*
 * @author kongweixiang
 * @version 1.0.0
 */
package com.kwxyzk.timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author kongweixiang
 * @date 2020/5/18
 * @since 1.0.0
 */
@Service
public class OrderPayTimeoutService {

    private static final Logger log = LoggerFactory.getLogger(HashedWheelTimer.class);

    private ConcurrentHashMap<String,Timeout> timeoutTask = new ConcurrentHashMap();

//    @Autowired
//    private EurekaClient eurekaClient;

    private final String service = "ORDER-CORE";

    private final HashedWheelTimer hashedWheelTime = new HashedWheelTimer(new NamedThreadFactory("OrderPayTimeoutTimer", true),1000, TimeUnit.MILLISECONDS,128);

    public void createTimeOutTask(String orderNo, long delay, TimeUnit unit) {
        OrderPayTimeoutTimerTask orderPayTimeoutTimerTask = new OrderPayTimeoutTimerTask(this, orderNo);
        Timeout timeout = hashedWheelTime.newTimeout(orderPayTimeoutTimerTask, delay, unit);
        timeoutTask.put(orderNo, timeout);

    }

    public void createTimeOutTask(String orderNo,long delay) {
        OrderPayTimeoutTimerTask orderPayTimeoutTimerTask = new OrderPayTimeoutTimerTask(this, orderNo);
        hashedWheelTime.newTimeout(orderPayTimeoutTimerTask, 30, TimeUnit.SECONDS);
        this.createTimeOutTask(orderNo, delay, TimeUnit.SECONDS);
    }
    public void createTimeOutTask(String orderNo) {
        this.createTimeOutTask(orderNo, 30, TimeUnit.SECONDS);
    }

    /**
     * 支付成功后本地调用
     * @param orderNo
     */
    public void cancel(String orderNo) {
        this.cancel(orderNo,true);
    }

    public void cancel(String orderNo,boolean local){
        //判断任务是否在本地
        Timeout timeout = timeoutTask.get(orderNo);
        if (timeout!=null && !timeout.isCancelled()) {
            timeoutTask.get(orderNo);
            timeout.cancel();
            timeoutTask.remove(orderNo);
        }else if(local){
            //通过eurake 遍历所有实列取消
//            Application app = eurekaClient.getApplication(service);
//            if (null != app){
//                List<InstanceInfo> result = app.getInstances();
//                String lastInstance = "";
//                RestTemplate restTemplate = new RestTemplate();
//                for(InstanceInfo instanceInfo : result) {
//                    lastInstance = instanceInfo.getHomePageUrl();
//                    String url = lastInstance + "orderTimeOutTask/cancel/"+orderNo;
//                    log.debug("cancel orderNo {} orderTimeoutTask remote start ...",orderNo);
//                    try {
//                        restTemplate.getForObject(url, String.class);
//                    } catch (Exception e) {
//                        log.error("cancel orderNo {} orderTimeoutTask remote error",orderNo,e);
//                    }
//                    log.debug("cancel orderNo {} orderTimeoutTask remote end ...",orderNo);
//                }
//            }

        }

    }

    public long getTaskSize(){
        return this.timeoutTask.size();
    }

    public void finishTask(String orderNo) {
        this.timeoutTask.remove(orderNo);
    }




    static class NamedThreadFactory implements ThreadFactory {

        protected static final AtomicInteger POOL_SEQ = new AtomicInteger(1);

        protected final AtomicInteger mThreadNum = new AtomicInteger(1);

        protected final String mPrefix;

        protected final boolean mDaemon;

        protected final ThreadGroup mGroup;

        public NamedThreadFactory() {
            this("pool-" + POOL_SEQ.getAndIncrement(), false);
        }

        public NamedThreadFactory(String prefix) {
            this(prefix, false);
        }

        public NamedThreadFactory(String prefix, boolean daemon) {
            mPrefix = prefix + "-thread-";
            mDaemon = daemon;
            SecurityManager s = System.getSecurityManager();
            mGroup = (s == null) ? Thread.currentThread().getThreadGroup() : s.getThreadGroup();
        }

        @Override
        public Thread newThread(Runnable runnable) {
            String name = mPrefix + mThreadNum.getAndIncrement();
            Thread ret = new Thread(mGroup, runnable, name, 0);
            ret.setDaemon(mDaemon);
            return ret;
        }

        public ThreadGroup getThreadGroup() {
            return mGroup;
        }
    }



}
