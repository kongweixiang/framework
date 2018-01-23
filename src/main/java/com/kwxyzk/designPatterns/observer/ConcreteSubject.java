package com.kwxyzk.designPatterns.observer;

public class ConcreteSubject extends Subject {
    /**
     * 具体的业务
     */
    public void doSomething() {
        /*
        业务处理
         */
        super.notifyObservers();
    }
}
