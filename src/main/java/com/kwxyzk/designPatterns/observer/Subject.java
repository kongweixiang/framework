package com.kwxyzk.designPatterns.observer;

import java.util.Vector;

public abstract class Subject {
    private Vector<Observer> vector = new Vector<Observer>();

    public void addObserver(Observer observer) {
        this.vector.add(observer);
    }

    public void delObserver(Observer observer) {
        this.vector.remove(observer);
    }

    public void notifyObservers() {
        for (Observer observer : vector) {
            observer.update();
        }
    }
}
