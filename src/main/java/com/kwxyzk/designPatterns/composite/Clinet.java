package com.kwxyzk.designPatterns.composite;

public class Clinet {
    public static void main(String[] args) {
        //创建一个根节点
        Composite root = new Composite();
        root.doSomething();

        //创建一个树枝节点
        Composite branch = new Composite();
        //创建一个叶子节点
        Leaf leaf = new Leaf();
        root.add(branch);
        branch.add(leaf);

    }

    //递归遍历树
    public static void display(Composite root) {
        for (Component c : root.getChlldren()) {
            if (c instanceof Leaf) {
                c.doSomething();
            } else {
                display((Composite)c);
            }

        }
    }
}
