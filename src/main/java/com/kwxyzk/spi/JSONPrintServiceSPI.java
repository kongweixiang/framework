/*
 * Company 上海来伊份电子商务有限公司。
 * @author kongweixiang
 * @version 1.0.0
 */
package com.kwxyzk.spi;

import com.alibaba.fastjson.JSON;

/**
 * @author kongweixiang
 * @date 2019/9/18
 * @since 1.0.0
 */
public class JSONPrintServiceSPI implements IPrintServiceSPI {
    @Override
    public void print(Object o) {
        System.out.println(getClass().getSimpleName()+":"+JSON.toJSONString(o));
    }
}
