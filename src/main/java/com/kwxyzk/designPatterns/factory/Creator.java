package com.kwxyzk.designPatterns.factory;

public abstract class Creator {
    /**
     * 创建一个产品对象，参数可以自行决定，class,Sring,Enum都可以，也可为空
     *
     */
    public abstract <T extends Product> T createProduct(Class<T> cla);
}
