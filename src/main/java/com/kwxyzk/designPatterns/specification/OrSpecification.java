package com.kwxyzk.designPatterns.specification;

public class OrSpecification extends CompositeSpecification {
    private ISpecification left;
    private ISpecification right;

    public OrSpecification(ISpecification left, ISpecification right) {
        this.left = left;
        this.right = right;
    }

    public boolean isSatisfiedBy(Object candidate) {
        return left.isSatisfiedBy(candidate) || right.isSatisfiedBy(candidate);
    }
}
