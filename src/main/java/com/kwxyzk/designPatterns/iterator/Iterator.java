package com.kwxyzk.designPatterns.iterator;

public interface Iterator {
    Object next();

    boolean hasNext();

    boolean remove();
}
