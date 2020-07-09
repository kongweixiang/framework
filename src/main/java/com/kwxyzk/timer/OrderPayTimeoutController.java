/*
 * @author kongweixiang
 * @version 1.0.0
 */
package com.kwxyzk.timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

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
@RequestMapping("/orderTimeOutTask")
public class OrderPayTimeoutController {


    @Autowired
    OrderPayTimeoutService orderPayTimeoutService;

    @GetMapping("/cancel/{orderNo}")
    public void cancel(@PathVariable("orderNo") String orderNo){
        orderPayTimeoutService.cancel(orderNo, false);
    }



}
