package com.kwxyzk.designPatterns.pool;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public abstract class ObjectPool<T> {
    private Map<T, ObjectStatus> pool = new ConcurrentHashMap<T, ObjectStatus>();

    public ObjectPool() {
        this.pool.put(create(), new ObjectStatus());
    }

    /**
     * 从池中取出对象
     * @return
     */
    public synchronized T checkOut() {
        for (T t : pool.keySet()) {
            if (pool.get(t).validate()) {
                pool.get(t).setUsing();
                return t;
            }
        }
        return null;
    }

    /**
     * 归还对象
     * */
    public synchronized void checkIn(T t) {
        pool.get(t).setFree();
    }


    public abstract T create();

    class ObjectStatus{
        /**
         * 占用
         * */
        public void setUsing() {

        }
        /**
         * 释放
         * */
        public void setFree() {

        }
        /**
         * 价差是否可用
         * */
        public boolean validate() {
            return false;
        }
    }

}

