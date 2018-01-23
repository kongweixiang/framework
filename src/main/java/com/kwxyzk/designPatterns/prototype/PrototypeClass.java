package com.kwxyzk.designPatterns.prototype;

public class PrototypeClass implements Cloneable {

    @Override
    protected PrototypeClass clone() {
        PrototypeClass prototypeClass = null;

        try {
            prototypeClass = (PrototypeClass) super.clone();
        } catch (CloneNotSupportedException e) {
            e.printStackTrace();
        }
        return prototypeClass;
    }
}
