package com.kwxyzk.designPatterns.templateMethod;

public class ConcreteTemplate2 extends AbstactTemplate {
    protected void doSomething() {
        System.out.println("ConcreteTemplate2 doSomething");
    }

    protected void doAnything() {
        System.out.println("ConcreteTemplate2 doAnything");
    }
}
