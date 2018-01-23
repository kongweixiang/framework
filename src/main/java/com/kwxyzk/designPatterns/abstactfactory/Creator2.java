package com.kwxyzk.designPatterns.abstactfactory;

public class Creator2 extends AbstactCreator {
    public AbstactProductA createProductA() {
        return new ProductA2();
    }

    public AbstactProductB createProductB() {
        return new ProductB2();
    }
}
