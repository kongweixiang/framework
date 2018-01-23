package com.kwxyzk.designPatterns.flyweight;

public abstract class FlyWeight {
    private String intrisic;
    protected final String Extrisic;

    //要求享元角色必须接受外部状态
    public FlyWeight(String extrisic) {
        this.Extrisic = extrisic;
    }

    //定义业务操作
    public abstract void opetate();

    public String getIntrisic() {
        return intrisic;
    }

    public void setIntrisic(String intrisic) {
        this.intrisic = intrisic;
    }
}
