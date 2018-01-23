package com.kwxyzk.designPatterns.builder;

public class Director {
    private Builder builder = new ConcreteBuilder();

    public Product getProduct() {
        builder.setPart();
        /*
        设置不同的零件，产生不同的产品
         */
        return builder.buildProduct();
    }

}
