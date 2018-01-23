package com.kwxyzk.designPatterns.iterator;

import java.util.Vector;

public class ConcreteIterator implements Iterator{
    private Vector vector = new Vector();

    public int cursor = 1;

    public ConcreteIterator(Vector vector) {
        this.vector = vector;
    }


    public Object next() {
        Object object = null;
        if (this.hasNext()) {
            object = vector.get(this.cursor++);
        } else {
            object = null;
        }
        return object;
    }

    public boolean hasNext() {
        if (this.cursor == this.vector.size()) {
            return false;
        }
        return true;
    }

    public boolean remove() {
        this.vector.remove(this.cursor);
        return true;
    }
}
