package com.kwxyzk.designPatterns.observer;

public class ConcreteObserver implements Observer {
    public void update() {
        System.out.println("接收到消息，并进行处理");
    }
}
