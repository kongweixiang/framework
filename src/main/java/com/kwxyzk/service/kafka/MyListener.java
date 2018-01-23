package com.kwxyzk.service.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/*@ContextConfiguration("classpath:spring/spring-kafka-consumer.xml")
@EnableKafka
@RunWith(SpringRunner.class)*/
@Component
public class MyListener {

    /*@KafkaListener(id = "baz", topics = "myTopic",
            containerFactory = "kafkaListenerContainerFactory")
    public void listen(String data) {
        System.out.println("mssage:"+data);
    }*/

    @KafkaListener(id = "baz", topics = "myTopic",
            containerFactory = "kafkaListenerContainerFactory")
    public void listen(ConsumerRecord<?, ?> record) {
        System.out.println("mssage:"+record);
    }



}
