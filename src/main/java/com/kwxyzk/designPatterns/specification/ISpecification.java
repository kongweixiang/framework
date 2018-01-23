package com.kwxyzk.designPatterns.specification;

public interface ISpecification {

    /**
     * 候选者是否满足要求
     * */
    boolean isSatisfiedBy(Object candidate);

    /**
     * and 操作
    */
    ISpecification and(ISpecification spec);

    /**
     * or 操作
     */
    ISpecification or(ISpecification spec);

    /**
     * not 操作
     * */
    ISpecification not(ISpecification spec);
}
