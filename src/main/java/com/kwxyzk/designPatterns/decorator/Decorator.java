package com.kwxyzk.designPatterns.decorator;

public abstract class Decorator extends Component {
    private Component component;

    public Decorator(Component component) {
        this.component = component;
    }

    @Override
    public void operate() {
        this.component.operate();
    }
}
