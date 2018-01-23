package com.kwxyzk.designPatterns.bridge;

public class RefinedAbstraction extends Abstraction {
    public RefinedAbstraction(Implementor implementor) {
        super(implementor);
    }

    //修正父类的行为
    public void request() {
        /*
         *业务处理
         */
        super.request();
        super.getImplementor().doSomething();
    }

}
