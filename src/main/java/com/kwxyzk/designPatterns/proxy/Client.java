package com.kwxyzk.designPatterns.proxy;

import java.lang.reflect.InvocationHandler;

public class Client {
    public static void main(String[] args) {
        Subject subject = new RealSubject();
        InvocationHandler handler = new MyInvocationHandler(subject);
        Subject proxy = DynamicProxy.newProxyInstance(subject.getClass().getClassLoader(), subject.getClass().getInterfaces(), handler);
        proxy.request();

        Subject proxy2 = SubjectDynamicProxy.newProxyInstance(subject.getClass().getClassLoader(), subject.getClass().getInterfaces(), handler);
        proxy2.request();
        proxy2.request();

    }
}
