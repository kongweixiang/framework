package com.kwxyzk.designPatterns.state;

public class Context {

    public final static State STATE1 = new CurrentState1();
    public final static State STATE2 = new CurrentState2();

    private State currentState;

    public State getCurrentState() {
        return currentState;
    }

    public void setCurrentState(State currentState) {
        this.currentState = currentState;
        this.currentState.setContext(this);
    }

    public void handle1(){
        this.currentState.handle1();
    }
    public void handle2() {
        this.currentState.handle2();
    }
}
