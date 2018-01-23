package com.kwxyzk.designPatterns.iterator;

public class Client {


    public static void main(String[] args) {
        Aggregate aggregate = new ConcreteAggregate();
        aggregate.add("as");
        aggregate.add("sdf");
        aggregate.add("sfs");
        Iterator iterator = aggregate.iterator();
        while (iterator.hasNext()) {
            System.out.println(iterator.next());

        }
    }
}
