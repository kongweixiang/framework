package com.kwxyzk.designPatterns.visitor;

public class ConcreteElement1 extends Element {
    public void doSomething() {
        //业务处理
        System.out.println("ConcreteElement1 doSomething");
    }

    /**
     * 允许哪个访问者访问
     * @param visitor
     */
    public void accept(IVisitor visitor) {
        visitor.visit(this);

    }
}
