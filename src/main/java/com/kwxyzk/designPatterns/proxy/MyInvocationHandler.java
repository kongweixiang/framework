package com.kwxyzk.designPatterns.proxy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

public class MyInvocationHandler implements InvocationHandler {
    //被代理的对象
    private Subject target;

    public MyInvocationHandler(Subject subject) {
        this.target = subject;
    }

    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        System.out.println("invoke");
        return method.invoke(this.target, args);
    }
}
