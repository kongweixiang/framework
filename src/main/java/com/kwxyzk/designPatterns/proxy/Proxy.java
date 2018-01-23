package com.kwxyzk.designPatterns.proxy;

public class Proxy implements Subject {
    private Subject subject = null;//要代理哪个实体类

    public void request() {
        this.before();
        subject.request();
        this.after();
    }

    public Proxy(Object... objects) {

    }

    public Proxy(Subject _subject) {
        this.subject = _subject;
    }

    private void before() {

    }

    private void after() {

    }
}
