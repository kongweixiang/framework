package com.kwxyzk.designPatterns.mediator;

public class ConcreteColleague2 extends Colleague {
    //通过构造函数注入中介者
    public ConcreteColleague2(Mediator mediator) {
        super(mediator);
    }

    //自有方法 self_method
    public void seltMethod() {

    }

    //依赖方法 dep_method
    public void depMethod() {
        //处理自己的业务
        //自己不能处理的业务逻辑，委托给中介者处理
        mediator.doSomething1();
    }

}
