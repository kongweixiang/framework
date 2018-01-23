package com.kwxyzk.designPatterns.memento;

public class Caretaker {
    private Memeto memeto;

    public Memeto getMemeto() {
        return memeto;
    }

    public void setMemeto(Memeto memeto) {
        this.memeto = memeto;
    }
}
