package com.kwxyzk.designPatterns.templateMethod;

public abstract class AbstactTemplate {

    /**
     * 基本方法
     */
    protected abstract void doSomething();

    /**
     * 基本方法
     */
    protected abstract void doAnything();

    /**
     * 模板方法
     */
    public void templateMethod() {
        this.doAnything();
        this.doSomething();
    }
}
