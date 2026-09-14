package com.hmdp.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.RabbitMQTopicConfig;
import com.hmdp.rebbitmq.MQReceiver;
import com.hmdp.service.*;
import com.rabbitmq.client.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 必须用预先创建的独立测试 vhost；绝不向默认业务 vhost 投递测试消息。 */
@EnabledIfEnvironmentVariable(named="RUN_RABBIT_INTEGRATION",matches="true")
class OpsBrokerIntegrationTest {
    @Test void realBrokerReplayConfirmsThenConsumerAcknowledges() throws Exception {
        String vhost=System.getenv("OPS_TEST_VHOST");assertNotNull(vhost);assertTrue(vhost.startsWith("hmdp-ops-test-"));
        var cf=new CachingConnectionFactory("127.0.0.1",5672);cf.setVirtualHost(vhost);
        cf.setUsername(System.getenv().getOrDefault("RABBITMQ_USERNAME","hmdp"));
        cf.setPassword(System.getenv().getOrDefault("RABBITMQ_PASSWORD","123456"));
        var mq=new MqOperationsService(cf);
        try(Channel consumer=mq.channel()) {
            consumer.queueDeclare(RabbitMQTopicConfig.QUEUE,true,false,false,null);
            consumer.queueDeclare(RabbitMQTopicConfig.DLQ,true,false,false,null);
            var orders=mock(IVoucherOrderService.class);when(orders.consumeOrder(any())).thenReturn("CONSUMED");
            var audit=mock(OpsAuditService.class);var parser=new OrderMessageService(new ObjectMapper());
            var receiver=new MQReceiver(orders,parser,audit,mq);
            CountDownLatch handled=new CountDownLatch(1);
            var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
            String consumerTag=consumer.basicConsume(RabbitMQTopicConfig.QUEUE,false,(tag,delivery)->{
                try {
                    var properties=new MessageProperties();properties.setDeliveryTag(delivery.getEnvelope().getDeliveryTag());
                    if(delivery.getProperties().getHeaders()!=null)properties.getHeaders().putAll(delivery.getProperties().getHeaders());
                    receiver.receiveSeckillMessage(new Message(delivery.getBody(),properties),consumer);
                }catch(Throwable ex){failure.set(ex);}finally{handled.countDown();}
            },tag->{});
            String id=UUID.randomUUID().toString(),incident=UUID.randomUUID().toString();
            var store=mock(OpsActionStore.class);when(store.owned(id,7L,"local")).thenReturn(Map.of("status","PENDING","incident_id",incident,"batch_size",1));
            when(store.claim(id,7L,"local",true)).thenReturn(true);
            var evidence=mock(OpsEvidenceService.class);when(evidence.databaseHealthy()).thenReturn(true);
            var props=new OpsProperties();props.setEnabled(true);props.setDiagnosticians(Set.of(7L));props.setOperators(Set.of(7L));
            var workflow=new OpsWorkflowService(props,evidence,store,mq,parser,new ObjectMapper());
            mq.publishConfirmed(RabbitMQTopicConfig.DLQ,"{\"id\":10,\"userId\":7,\"voucherId\":1}".getBytes(),Map.of());
            assertEquals(1,mq.state(true).ready());
            workflow.decide(7,id,true,true);
            assertTrue(handled.await(10,TimeUnit.SECONDS));assertNull(failure.get());
            assertEquals(0,mq.state(true).ready());
            verify(orders,times(1)).consumeOrder(any());verify(audit).event(anyString(),eq("CONSUMED"),eq("OK"),eq(id),eq(10L));
            verify(store).finish(eq(id),eq("PUBLISHED"),anyString(),eq(true));
            consumer.basicCancel(consumerTag);
        } finally { cf.destroy(); }
    }
}
