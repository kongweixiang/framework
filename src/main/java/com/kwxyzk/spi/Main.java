/*
 * Company 上海来伊份电子商务有限公司。
 * @author kongweixiang
 * @version 1.0.0
 */
package com.kwxyzk.spi;

import java.util.Iterator;
import java.util.ServiceLoader;

/**
 * @author kongweixiang
 * @date 2019/9/18
 * @since 1.0.0
 */
public class Main {

    public static void main(String[] args) {
        ServiceLoader<IPrintServiceSPI> serviceLoader = ServiceLoader.load(IPrintServiceSPI.class);
        Iterator<IPrintServiceSPI> iterator = serviceLoader.iterator();
        while (iterator.hasNext()) {
            iterator.next().print("test");
        }
    }
}
