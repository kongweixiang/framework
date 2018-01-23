package com.kwxyzk.designPatterns.state;

public class Client {
    public static void main(String[] args) {
        Context context = new Context();
        context.setCurrentState(new CurrentState1());
        context.handle1();
        context.handle2();
    }
}
