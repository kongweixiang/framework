package com.kwxyzk.designPatterns.expression;

import java.util.HashMap;
import java.util.Scanner;

public class Client {

    public static void main(String[] args) {
        String expStr = getExpStr();
        HashMap<String, Integer> var = getValue(expStr);
        Calculator cal = new Calculator(expStr);
        System.out.println("运算结果为：" + expStr + "=" + cal.run(var));
    }


    public static String getExpStr() {
        System.out.println("请输入表达式：");
        return (new Scanner(System.in)).nextLine();
    }

    public static HashMap<String, Integer> getValue(String expStr) {
        HashMap<String, Integer> map = new HashMap<String, Integer>();
        for (char ch : expStr.toCharArray()) {
            if (ch != '+' && ch != '-') {
                if (!map.containsKey(String.valueOf(ch))) {
                    System.out.println("请输入"+ ch + "的值:");
                    String in = new Scanner(System.in).nextLine();
                    map.put(String.valueOf(ch), Integer.valueOf(in));
                }
            }
        }
        return map;
    }

}
