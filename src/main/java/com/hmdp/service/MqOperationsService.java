package com.hmdp.service;

import com.hmdp.config.RabbitMQTopicConfig;
import com.rabbitmq.client.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** 受控 broker 适配 Service，队列不接受用户或模型输入。 */
@Service
public class MqOperationsService {
    public record QueueState(String queue, boolean available, long ready, int consumers) { }
    private final ConnectionFactory connections;
    public MqOperationsService(ConnectionFactory connections) { this.connections=connections; }
    public Channel channel() { return connections.createConnection().createChannel(false); }
    public QueueState state(boolean dead) {
        String name=dead ? RabbitMQTopicConfig.DLQ : RabbitMQTopicConfig.QUEUE;
        try(Channel channel=channel()) {
            var result=channel.queueDeclarePassive(name);
            return new QueueState(name,true,result.getMessageCount(),result.getConsumerCount());
        } catch(Exception ex) { return new QueueState(name,false,-1,-1); }
    }
    public void publishConfirmed(String queue, byte[] body, Map<String,Object> headers) throws Exception {
        if(!queue.equals(RabbitMQTopicConfig.QUEUE) && !queue.equals(RabbitMQTopicConfig.DLQ)) throw new IllegalArgumentException();
        try(Channel channel=channel()) {
            channel.queueDeclarePassive(queue);
            channel.confirmSelect();
            AtomicBoolean returned=new AtomicBoolean();
            ReturnListener listener=(code,text,exchange,routing,properties,payload)->returned.set(true);
            channel.addReturnListener(listener);
            try {
                channel.basicPublish("",queue,true,new AMQP.BasicProperties.Builder().deliveryMode(2)
                        .contentType("application/json").headers(headers).build(),body);
                channel.waitForConfirmsOrDie(5000);
                if(returned.get()) throw new IllegalStateException("UNROUTABLE");
            } finally { channel.removeReturnListener(listener); }
        }
    }
}
