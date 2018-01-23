package com.kwxyzk.designPatterns.singleton;

public enum Singleton5 {
    singleton;

    private Singleton5() {

    }

    public Singleton5 getInstance() {
        return singleton;
    }
}
