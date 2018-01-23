package com.kwxyzk.util.kafka;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.config.ContainerProperties;
import org.springframework.kafka.support.SendResult;
import org.springframework.util.StringUtils;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureCallback;

import java.util.HashMap;
import java.util.Map;

public class KafkaContext {
    @Bean
    public KafkaAdmin admin() {
        Map<String, Object> configs = new HashMap<String,Object>();

        configs.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
//                StringUtils.arrayToCommaDelimitedString(kafkaEmbedded().getBrokerAddresses()));
                StringUtils.arrayToCommaDelimitedString(new Object[]{}));
                //TODO
        return new KafkaAdmin(configs);
    }

    @Bean
    public NewTopic topic1() {
        return new NewTopic("foo", 10, (short) 2);
    }

    @Bean
    public NewTopic topic2() {
        return new NewTopic("bar", 10, (short) 2);
    }
    @Bean
    KafkaMessageListenerContainer container(ConsumerFactory<String, String> cf,
                                            final KafkaTemplate template) {
        ContainerProperties props = new ContainerProperties("foo");
        props.setGroupId("group");
        //TODO
        return new KafkaMessageListenerContainer(cf, props);
    }

    //发送监听方法级实现
    public void template(KafkaTemplate template){
        ListenableFuture<SendResult<Integer, String>> future = template.send("","","foo");
        future.addCallback(new ListenableFutureCallback<SendResult<Integer, String>>() {

            @Override
            public void onSuccess(SendResult<Integer, String> result) {
            }

            @Override
            public void onFailure(Throwable ex) {
            }

        });
    }
}
