/*
 * @author kongweixiang
 * @version 1.0.0
 */
package com.kwxyzk.timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author kongweixiang
 * @date 2020/5/18
 * @since 1.0.0
 */
public class OrderPayTimeoutTimerTask implements TimerTask {
    private static final Logger log = LoggerFactory.getLogger(HashedWheelTimer.class);

    private OrderPayTimeoutService orderPayTimeoutService;
    private String orderNo;


    public OrderPayTimeoutTimerTask(OrderPayTimeoutService orderPayTimeoutService,String orderNo) {
        this.orderPayTimeoutService = orderPayTimeoutService;
        this.orderNo = orderNo;
    }

    @Override
    public void run(Timeout timeout) throws Exception {
        //获取订单信息
        //getOrderInfo(this.orderNo)
        //判断订单时候已支付

        //未支付是订单加锁，取消订单
        log.info("订单{}取消", this.orderNo);
        this.orderPayTimeoutService.finishTask(this.orderNo);

        System.out.println("订单" + this.orderNo + "取消");

    }
}
