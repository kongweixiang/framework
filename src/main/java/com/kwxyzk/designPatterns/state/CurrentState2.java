package com.kwxyzk.designPatterns.state;

public class CurrentState2 extends State {
    public void handle1() {
        super.context.setCurrentState(Context.STATE1);
        super.context.handle1();
    }

    public void handle2() {
        System.out.println("CurrentState2 ... handle2");
    }
}
