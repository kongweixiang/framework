package com.kwxyzk.designPatterns.Adapter;

public class Adapter extends Adptee implements Target {

    public void request() {
        super.doSomething();
    }
}
