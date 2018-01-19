package com.kwxyzk.test;

import org.junit.Test;

import java.util.regex.Pattern;

public class test {

    @Test
    public void test() {
        String pattern = "http://117.29.183.234:8091/school?";
        Pattern p = Pattern.compile(pattern);
        System.out.println(p.matcher(pattern).matches());

    }
}
