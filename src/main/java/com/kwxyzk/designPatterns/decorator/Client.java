package com.kwxyzk.designPatterns.decorator;

public class Client {
    public static void main(String[] args) {
        Component component = new ConcreateComponent();
        //第一次装饰
        component = new ConcreteDecorator1(component);
        //第二次装饰
        component = new ConcreteDecorator2(component);
        //装饰后运行
        component.operate();
    }
}
