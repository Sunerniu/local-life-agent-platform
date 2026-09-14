package com.hmdp.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;

@Slf4j
@Configuration
public class ShopCacheConfiguration {
    @Bean
    public RedisMessageListenerContainer shopCacheListenerContainer(
            RedisConnectionFactory connectionFactory, ShopCache cache,
            @Value("${hmdp.shop-cache.channel:hmdp:shop:invalidate}") String channel) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            try {
                cache.invalidateLocal(Long.valueOf(new String(message.getBody(), StandardCharsets.UTF_8)));
            } catch (NumberFormatException e) {
                log.warn("忽略格式错误的店铺缓存失效通知");
            }
        }, new ChannelTopic(channel));
        return container;
    }
}
