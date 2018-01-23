package com.kwxyzk.designPatterns.memento;

public class Client {
    public static void main(String[] args) {
        Originator originator = new Originator();
        Caretaker caretaker = new Caretaker();
        caretaker.setMemeto(originator.createMemento());
        originator.restoreMemento(caretaker.getMemeto());
    }
}
