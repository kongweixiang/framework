/*
 * @author kongweixiang
 * @version 1.0.0
 */
package com.kwxyzk.jvm;

/**
 * @author kongweixiang
 * @date 2021/2/26
 * @since 1.0.0
 */
@Create
public class Test {
    private String message;

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public static void main(String[] args) {
        Test test = new Test();
        test.setMessage("message");
        System.out.println(test.getMessage());
    }

}
