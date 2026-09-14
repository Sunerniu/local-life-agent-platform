package com.hmdp.agent;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class OpenAIConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "hmdp.agent", name = "enabled", havingValue = "true")
    public OpenAIClient openAIClient(AgentProperties properties) {
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null || key.isBlank() || properties.getModel().isBlank()) {
            throw new IllegalStateException("启用 Agent 需要 OPENAI_API_KEY 和 OPENAI_MODEL 环境变量");
        }
        return OpenAIOkHttpClient.builder().apiKey(key).baseUrl(properties.getBaseUrl())
                .timeout(Duration.ofSeconds(30)).maxRetries(1).build();
    }
}
