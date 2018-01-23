package com.kwxyzk.test.kafka;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.testng.AbstractTestNGSpringContextTests;
import org.testng.annotations.Test;

@ContextConfiguration("classpath:spring/spring-kafka-producer.xml")
public class producerTest extends AbstractTestNGSpringContextTests {

    @Autowired
    KafkaTemplate kafkaTemplate;

    @Test
    public void send() {
        kafkaTemplate.send("myTopic","key","value");
    }
}
