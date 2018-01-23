package com.kwxyzk.designPatterns.servant;

public class Servant {
    public void service(IServiced serviceFuture) {
        serviceFuture.serviced();
    }
}
