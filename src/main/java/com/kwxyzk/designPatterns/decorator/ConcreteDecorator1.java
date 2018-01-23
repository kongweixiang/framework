package com.kwxyzk.designPatterns.decorator;

public class ConcreteDecorator1 extends Decorator {
    public ConcreteDecorator1(Component component) {
        super(component);
    }

    private void method1() {
        System.out.println("method1 装饰");

    }

    @Override
    public void operate() {
        this.method1();
        super.operate();
    }
}
