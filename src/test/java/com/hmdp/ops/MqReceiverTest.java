package com.hmdp.ops;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.service.*;
import com.hmdp.rebbitmq.MQReceiver;
import com.hmdp.config.RabbitMQTopicConfig;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
class MqReceiverTest {
    @Test void acknowledgesOnlyAfterCommittedServiceAndAudit() throws Exception {
        var orders=mock(IVoucherOrderService.class);var audit=mock(OpsAuditService.class);var mq=mock(MqOperationsService.class);var ch=mock(Channel.class);
        when(orders.consumeOrder(any())).thenReturn("CONSUMED");
        var msg=new Message("{\"id\":10,\"userId\":7,\"voucherId\":1}".getBytes(),new MessageProperties());msg.getMessageProperties().setDeliveryTag(4);
        new MQReceiver(orders,new OrderMessageService(new ObjectMapper()),audit,mq).receiveSeckillMessage(msg,ch);
        var sequence=inOrder(orders,audit,ch);sequence.verify(orders).consumeOrder(any());sequence.verify(audit).event(anyString(),eq("CONSUMED"),eq("OK"),isNull(),eq(10L));sequence.verify(ch).basicAck(4,false);
        verifyNoInteractions(mq);
    }
    @Test void poisonMessageGoesToDlqWithPublisherConfirmation() throws Exception {
        var orders=mock(IVoucherOrderService.class);var audit=mock(OpsAuditService.class);var mq=mock(MqOperationsService.class);var ch=mock(Channel.class);
        var msg=new Message("not-json".getBytes(),new MessageProperties());msg.getMessageProperties().setDeliveryTag(4);
        new MQReceiver(orders,new OrderMessageService(new ObjectMapper()),audit,mq).receiveSeckillMessage(msg,ch);
        var sequence=inOrder(mq,ch);sequence.verify(mq).publishConfirmed(eq(RabbitMQTopicConfig.DLQ),any(),anyMap());sequence.verify(ch).basicAck(4,false);verifyNoInteractions(orders);
        verify(audit).event(anyString(),eq("FAILED"),eq("INVALID_ORDER_MESSAGE"),isNull(),isNull());
    }
}
