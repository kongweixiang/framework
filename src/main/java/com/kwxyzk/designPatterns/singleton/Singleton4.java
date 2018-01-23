package com.kwxyzk.designPatterns.singleton;

import java.io.ObjectStreamException;
import java.io.Serializable;

/**
 * 序列化下单例的实现
 */
public class Singleton4 implements Serializable{
    private static class Handle {
        private static Singleton4 singleton = new Singleton4();
    }
    public static Singleton4 getInstance4() {
        return Handle.singleton;
    }

    protected Object readResolve() throws ObjectStreamException{
        return Handle.singleton;
    }
}
