package com.kwxyzk.test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.testng.AbstractTestNGSpringContextTests;
import org.testng.annotations.Test;

@ContextConfiguration("classpath:spring/spring-singleRedis.xml")
public class redisTest extends AbstractTestNGSpringContextTests {
    @Autowired
    RedisTemplate redisTemplate;

    @Test
    public void tedisSpring() {
        ListOperations listOperations = redisTemplate.opsForList();
        listOperations.leftPush("list","22");
        System.out.println( listOperations.leftPop("list"));

    }


}
