package com.kwxyzk.util.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.PartitionOffset;
import org.springframework.kafka.annotation.TopicPartition;
import org.springframework.kafka.event.ListenerContainerIdleEvent;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;

import java.util.List;

public class Listener {

    @KafkaListener(id = "bar", topicPartitions =
            { @TopicPartition(topic = "topic1", partitions = { "0", "1" }),
                    @TopicPartition(topic = "topic2", partitions = "0",
                            partitionOffsets = @PartitionOffset(partition = "1", initialOffset = "100"))
            })
    public void listen(ConsumerRecord<?, ?> record) {
        //TODO
    }

    @KafkaListener(id = "baz", topics = "myTopic",
            containerFactory = "kafkaManualAckListenerContainerFactory")
    public void listen(String data, Acknowledgment ack) {
        //TODO
        ack.acknowledge();
    }

    @KafkaListener(id = "qux", topicPattern = "myTopic1")
    public void listen(@Payload String foo,
                       @Header(KafkaHeaders.RECEIVED_MESSAGE_KEY) Integer key,
                       @Header(KafkaHeaders.RECEIVED_PARTITION_ID) int partition,
                       @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                       @Header(KafkaHeaders.RECEIVED_TIMESTAMP) long ts
    ) {
        //TODO
    }




    @KafkaListener(id = "list", topics = "myTopic", containerFactory = "batchFactory")
    public void listen1(List<String> list) {
        //TODO
    }

    @KafkaListener(id = "list", topics = "myTopic", containerFactory = "batchFactory")
    public void listen(List<String> list,
                       @Header(KafkaHeaders.RECEIVED_MESSAGE_KEY) List<Integer> keys,
                       @Header(KafkaHeaders.RECEIVED_PARTITION_ID) List<Integer> partitions,
                       @Header(KafkaHeaders.RECEIVED_TOPIC) List<String> topics,
                       @Header(KafkaHeaders.OFFSET) List<Long> offsets) {
        //TODO
    }

    @KafkaListener(id = "listMsg", topics = "myTopic", containerFactory = "batchFactory")
    public void listen14(List<Message<?>> list) {
        //TODO
    }

    @KafkaListener(id = "listMsgAck", topics = "myTopic", containerFactory = "batchFactory")
    public void listen15(List<Message<?>> list, Acknowledgment ack) {
        //TODO
    }

    @KafkaListener(id = "listCRs", topics = "myTopic", containerFactory = "batchFactory")
    public void listen(List<ConsumerRecord<Integer, String>> list) {
        //TODO
    }

    @KafkaListener(id = "listCRsAck", topics = "myTopic", containerFactory = "batchFactory")
    public void listen(List<ConsumerRecord<Integer, String>> list, Acknowledgment ack) {
        //TODO
    }

    @KafkaListener(id = "multi", topics = "myTopic")
    static class MultiListenerBean {

        @KafkaHandler
        public void listen(String foo) {
            //TODO
        }

        @KafkaHandler
        public void listen(Integer bar) {
            //TODO
        }

    }

    @KafkaListener(id = "qux", topics = "annotated")
    public void listen4(@Payload String foo, Acknowledgment ack) {

    }

    ListenerContainerIdleEvent event;
    @EventListener(condition = "event.listenerId.startsWith('qux-')")
    public void eventHandler(ListenerContainerIdleEvent event) {
//        this.event = event;
//        eventLatch.countDown();
    }
}
