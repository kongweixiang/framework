package com.kwxyzk.designPatterns.abstactfactory;

public class Creator1 extends AbstactCreator {
    public AbstactProductA createProductA() {
        return new ProductA1();
    }

    public AbstactProductB createProductB() {
        return new ProductB1();
    }
}
