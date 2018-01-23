package com.kwxyzk.designPatterns.proxy;

public class BeforeAdvice implements IAdvice {
    public void exec() {
        System.out.println("BeforeAdvice exec do ");
    }
}
