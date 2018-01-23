package com.kwxyzk.designPatterns.factory;

public class ConcreteCreator extends Creator {
    public <T extends Product> T createProduct(Class<T> cla) {
        Product product = null;
        try {
            product = (Product) Class.forName(cla.getName()).newInstance();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
