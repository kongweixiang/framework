package com.kwxyzk.designPatterns.abstactfactory;

public class Client {
    public static void main(String[] args) {
        AbstactCreator creator1 = new Creator1();
        AbstactCreator creator2 = new Creator2();

        //产生等级为1的 产品
        AbstactProductA a1 = creator1.createProductA();
        AbstactProductB b1 = creator1.createProductB();

        //产生等级为2的产品
        AbstactProductA a2 = creator2.createProductA();
        AbstactProductB b2 = creator2.createProductB();
    }
}
