package com.kwxyzk.designPatterns.facade;

public class Facade {

    private ClassA a = new ClassA();
    private ClassB b = new ClassB();
    private ClassC c = new ClassC();

    public void MethodA() {
        a.doSomethingA();
    }
    public void MethodB() {
        b.doSomethingB();
    }
    public void MethodC() {
        c.doSomethingC();
    }

    private void Method() {
        a.doSomethingA();
        b.doSomethingB();
        c.doSomethingC();
    }

}
