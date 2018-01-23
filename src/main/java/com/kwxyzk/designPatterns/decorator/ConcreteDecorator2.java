package com.kwxyzk.designPatterns.decorator;

public class ConcreteDecorator2 extends Decorator {
    public ConcreteDecorator2(Component component) {
        super(component);
    }

    private void method2() {
        System.out.println("method2 装饰");

    }

    @Override
    public void operate() {
        this.method2();
        super.operate();
    }
}
