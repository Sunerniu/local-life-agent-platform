package com.hmdp.rebbitmq;

import com.hmdp.config.RabbitMQTopicConfig;
import com.hmdp.service.*;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import java.util.*;

@Slf4j
@Service
public class MQReceiver {
    private final IVoucherOrderService orders;
    private final OrderMessageService parser;
    private final OpsAuditService audit;
    private final MqOperationsService mq;
    public MQReceiver(IVoucherOrderService orders, OrderMessageService parser, OpsAuditService audit, MqOperationsService mq) {
        this.orders=orders; this.parser=parser; this.audit=audit; this.mq=mq;
    }
    @RabbitListener(queues=RabbitMQTopicConfig.QUEUE, ackMode="MANUAL")
    public void receiveSeckillMessage(Message message, Channel channel) throws Exception {
        String correlation=UUID.randomUUID().toString();
        Object rawAction=message.getMessageProperties().getHeaders().get("ops-action-id");
        String action=null;
        try { if(rawAction!=null) action=UUID.fromString(rawAction.toString()).toString(); } catch(IllegalArgumentException ignored) { }
        Long orderId=null;
        String failureCode="CONSUME_FAILED";
        for(int attempt=1;attempt<=3;attempt++) {
            try {
                var order=parser.parse(message.getBody()); orderId=order.getId();
                String outcome=orders.consumeOrder(order);
                audit.event(correlation,outcome,"OK",action,orderId);
                channel.basicAck(message.getMessageProperties().getDeliveryTag(),false);
                return;
            } catch(Exception ex) {
                failureCode=classify(ex);
                log.warn("order consume failed correlation={} attempt={} category={}",correlation,attempt,ex.getClass().getSimpleName());
                if(attempt<3) Thread.sleep(200L*attempt);
            }
        }
        try {
            audit.event(correlation,"FAILED",failureCode,action,orderId);
            mq.publishConfirmed(RabbitMQTopicConfig.DLQ,message.getBody(),Map.of("failure-correlation",correlation));
            channel.basicAck(message.getMessageProperties().getDeliveryTag(),false);
        } catch(Exception failure) {
            channel.basicNack(message.getMessageProperties().getDeliveryTag(),false,true);
            Thread.sleep(1000);
        }
    }
    private String classify(Exception error) {
        for(Throwable cause=error;cause!=null;cause=cause.getCause()) {
            if(cause instanceof java.sql.SQLException || cause instanceof org.springframework.dao.DataAccessException) return "DATABASE_ERROR";
            if(Set.of("INVALID_ORDER_MESSAGE","VOUCHER_NOT_FOUND","ORDER_ID_CONFLICT","NO_STOCK","ORDER_SAVE_FAILED").contains(String.valueOf(cause.getMessage()))) return cause.getMessage();
        }
        return "CONSUME_FAILED";
    }
}
