package com.kwxyzk.designPatterns.composite;

import java.util.ArrayList;

public class Composite extends Component {

    private ArrayList<Component> componentList = new ArrayList<Component>();

    public void add(Component component) {
        this.componentList.add(component);
    }

    public void remove(Component component) {
        this.componentList.remove(component);
    }

    public ArrayList<Component> getChlldren() {
        return this.componentList;
    }

}
