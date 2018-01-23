package com.kwxyzk.designPatterns.memento;

public class Originator {
    private String state;

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    /**
     * 创建一个备份
     * @return
     */
    public Memeto createMemento() {
        return new Memeto(this.state);
    }

    /**
     * 恢复一个备份
     * @param memeto
     */
    public void restoreMemento(Memeto memeto) {
        this.setState(memeto.getState());

    }
}
