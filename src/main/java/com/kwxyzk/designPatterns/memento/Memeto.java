package com.kwxyzk.designPatterns.memento;

public class Memeto {
    private String state;

    public Memeto(String state) {
        this.state = state;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }
}
