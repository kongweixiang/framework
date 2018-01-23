package com.kwxyzk.designPatterns.templateMethod;

public class ConcreteTemplate1 extends AbstactTemplate {
    protected void doSomething() {
        System.out.println("ConcreteTemplate1 doSomething");
    }

    protected void doAnything() {
        System.out.println("ConcreteTemplate1 doAnything");
    }
}
