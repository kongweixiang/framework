package com.kwxyzk.designPatterns.specification;

public class BizSpecification extends CompositeSpecification {
    private Object object;

    public BizSpecification(Object object) {
        this.object = object;
    }

    public boolean isSatisfiedBy(Object candidate) {
        return false;
    }
}
