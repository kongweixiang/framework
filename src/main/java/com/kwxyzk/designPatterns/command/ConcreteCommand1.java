package com.kwxyzk.designPatterns.command;

public class ConcreteCommand1 extends Command {
    //对哪个Receiver类进行命令处理
    private Receiver receiver;

    //通过构造传入接受者
    public ConcreteCommand1(Receiver receiver) {
        this.receiver = receiver;
    }

    public void execute() {
        //业务处理
        receiver.doSomething();
    }
}
