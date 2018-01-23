package com.kwxyzk.designPatterns.memento;

public class Originator2 implements Cloneable{
    private Originator2 back;

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
    public void createMemento() {
        this.back = this.clone();
    }

    /**
     * 恢复一个备份
     * @param memeto
     */
    public void restoreMemento(Memeto memeto) {
        this.setState(this.back.getState());

    }

    protected Originator2 clone() {
        try {
            return (Originator2) super.clone();
        } catch (CloneNotSupportedException e) {
            e.printStackTrace();
        }
        return null;
    }
}
