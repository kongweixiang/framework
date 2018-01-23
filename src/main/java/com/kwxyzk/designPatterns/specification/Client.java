package com.kwxyzk.designPatterns.specification;

import java.util.ArrayList;
import java.util.List;

public class Client {
    public static void main(String[] args) {
        List<Object> list = new ArrayList<Object>();
        ISpecification spec1 = new BizSpecification(new Object());
        ISpecification spec2 = new BizSpecification(new Object());
        for (Object object : list) {
            if (spec1.and(spec2).isSatisfiedBy(object)) {
                System.out.println(object);
            }
        }
    }
}
