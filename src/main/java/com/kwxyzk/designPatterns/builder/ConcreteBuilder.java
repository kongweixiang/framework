package com.kwxyzk.designPatterns.builder;

public class ConcreteBuilder extends Builder {
    private Product product = new Product();
    public void setPart() {
        /*
         * 产品内的逻辑处理
         */

    }

    public Product buildProduct() {
        return product;
    }

}
