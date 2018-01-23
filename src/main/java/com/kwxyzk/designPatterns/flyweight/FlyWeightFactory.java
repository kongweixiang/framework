package com.kwxyzk.designPatterns.flyweight;

import java.util.HashMap;

public class FlyWeightFactory {

    private static HashMap<String, FlyWeight> pool = new HashMap<String, FlyWeight>();

    public static FlyWeight getFlyWeight(String Extrisic) {
        FlyWeight flyWeight = null;

        if (pool.containsKey(Extrisic)) {
            flyWeight = pool.get(Extrisic);

        } else {
            //根据外部状态创建享元对象
            flyWeight = new ConcreteFlyWeight1(Extrisic);
            //放入池中
            pool.put(Extrisic, flyWeight);
        }
        return flyWeight;
    }

}
