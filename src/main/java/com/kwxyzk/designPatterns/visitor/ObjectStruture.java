package com.kwxyzk.designPatterns.visitor;

import java.util.Random;

public class ObjectStruture {

    public static Element createElement() {
        Random random = new Random();
        if (random.nextBoolean()) {
            return new ConcreteElement1();
        } else {
            return new ConcreteElement2();
        }
    }
}
