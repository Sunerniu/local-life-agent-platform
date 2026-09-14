package com.hmdp.agent;

import com.openai.client.OpenAIClient;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class OpenAIMerchantModel implements MerchantModel {
    private final ObjectProvider<OpenAIClient> client;
    public OpenAIMerchantModel(ObjectProvider<OpenAIClient> client) { this.client = client; }

    public ChatCompletionMessage complete(ChatCompletionCreateParams params) {
        OpenAIClient instance = client.getIfAvailable();
        if (instance == null) throw new AgentException(HttpStatus.SERVICE_UNAVAILABLE, "Agent 尚未启用");
        try {
            return instance.chat().completions().create(params).choices().stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("No choice")).message();
        } catch (RuntimeException ex) {
            // 不向客户端回传 SDK 原始异常、请求内容或密钥。
            throw new AgentException(HttpStatus.BAD_GATEWAY, "模型请求失败，请稍后重试");
        }
    }
}
