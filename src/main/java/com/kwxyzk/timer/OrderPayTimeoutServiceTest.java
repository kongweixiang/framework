/*
 * @author kongweixiang
 * @version 1.0.0
 */
package com.kwxyzk.timer;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * @author kongweixiang
 * @date 2020/5/18
 * @since 1.0.0
 */
public class OrderPayTimeoutServiceTest {

    public static void main(String[] args) {
        OrderPayTimeoutService service = new OrderPayTimeoutService();
        Random rd = new Random();
        for (int i = 0; i < 100; i++) {
            service.createTimeOutTask(String.valueOf(i), rd.nextInt(2), TimeUnit.SECONDS);
        }


        while (service.getTaskSize() != 0) {
        }
        System.out.println("测试完成");

//        System.out.println(normalizeTicksPerWheel(310));
    }

    public static int normalizeTicksPerWheel(int ticksPerWheel) {
        int normalizedTicksPerWheel = ticksPerWheel - 1;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 1;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 2;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 4;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 8;
         normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 16;
        return normalizedTicksPerWheel + 1;
    }
}
