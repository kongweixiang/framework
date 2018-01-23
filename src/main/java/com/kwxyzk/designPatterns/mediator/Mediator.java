package com.kwxyzk.designPatterns.mediator;

public abstract class Mediator {

    private ConcreteColleague1 colleague1;
    private ConcreteColleague2 colleague2;

    //通过getter/setter注入同事类
    public ConcreteColleague1 getColleague1() {
        return colleague1;
    }

    public void setColleague1(ConcreteColleague1 colleague1) {
        this.colleague1 = colleague1;
    }

    public ConcreteColleague2 getColleague2() {
        return colleague2;
    }

    public void setColleague2(ConcreteColleague2 colleague2) {
        this.colleague2 = colleague2;
    }

    //中介者模式的业务逻辑
    public abstract void doSomething1();

    public abstract void doSomething2();

}
