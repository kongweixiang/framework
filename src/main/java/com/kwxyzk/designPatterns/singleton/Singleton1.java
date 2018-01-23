package com.kwxyzk.designPatterns.singleton;

/**
 * 恶汉模式，线程安全
 */
public class Singleton1 {
    private static final Singleton1 singletoon1 = new Singleton1();
    private Singleton1() {

    }

    public static Singleton1 getInstance() {
        return singletoon1;
    }

    public static void doSomething(Object... objects) {

    }
}
