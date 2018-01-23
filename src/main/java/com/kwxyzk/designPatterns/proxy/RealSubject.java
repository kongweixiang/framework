package com.kwxyzk.designPatterns.proxy;

public class RealSubject implements Subject {
    public void request() {
        System.out.println("RealSubject request");
    }
}
