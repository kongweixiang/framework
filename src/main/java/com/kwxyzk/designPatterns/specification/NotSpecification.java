package com.kwxyzk.designPatterns.specification;

public class NotSpecification extends CompositeSpecification {
    private ISpecification spec;

    public NotSpecification(ISpecification spec) {
        this.spec = spec;
    }

    public boolean isSatisfiedBy(Object candidate) {
        return !spec.isSatisfiedBy(candidate);
    }
}
