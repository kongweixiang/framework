package com.kwxyzk.designPatterns.singleton;

/**
 * 线程安全的懒汉模式
 */
public class Singleton3 {

    private static Singleton2 singleton2;

    /**
     * 解决方案一，synchronized同步方法，效率低
     * @return
     */
    public synchronized static Singleton2 getInstance() {
        if (singleton2 == null) {
            singleton2 = new Singleton2();
        }
        return singleton2;
    }

    /**
     * 解决方案二，synchronized同步代码块
     * @return
     */
    public static Singleton2 getInstance2() {
        synchronized (Singleton2.class) {
            if (singleton2 == null) {
                singleton2 = new Singleton2();
            }
        }

        return singleton2;
    }

    /**
     * 解决方案三使用DLC双检查锁机制
     * @return
     */
    public static Singleton2 getInstance3() {
            if (singleton2 != null) {

            }else{//第一次价检查
                synchronized (Singleton2.class) {
                    if (singleton2 == null) {//第二次检查
                        singleton2 = new Singleton2();
                    }
            }
        }
        return singleton2;
    }

    /**
     * 解决方案四，使用静态内部类
     * @return
     */
    private static class Handle {
        private static Singleton2 singleton2 = new Singleton2();
    }
    public static Singleton2 getInstance4() {
        return Handle.singleton2;
    }

    /**
     * 解决方案五，使用静态代码块
     */
    static {
        singleton2 = new Singleton2();
    }
    public static Singleton2 getInstace5() {
        return singleton2;
    }
}
