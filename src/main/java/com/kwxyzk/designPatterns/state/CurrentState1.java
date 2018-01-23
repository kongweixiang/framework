package com.kwxyzk.designPatterns.state;

public class CurrentState1 extends State {
    public void handle1() {
        System.out.println("CurrentState1 ... handle1");
    }

    public void handle2() {
        super.context.setCurrentState(Context.STATE2);
        super.context.handle2();
    }
}
