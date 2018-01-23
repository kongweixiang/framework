package com.kwxyzk.designPatterns.templateMethod;

public class Client {

    public static void main(String[] args) {
        AbstactTemplate abstactTemplate1 = new ConcreteTemplate1();
        AbstactTemplate abstactTemplate2 = new ConcreteTemplate2();
        abstactTemplate1.templateMethod();
        abstactTemplate2.templateMethod();
    }
}
