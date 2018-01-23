package com.kwxyzk.designPatterns.singleton;

/**
 * 懒汉模式，非线程安全
 */
public class Singleton2 {
    private static Singleton2 singleton2;

    public static Singleton2 getInstance() {
        if (singleton2 == null) {
            singleton2 = new Singleton2();
        }
        return singleton2;
    }
}
