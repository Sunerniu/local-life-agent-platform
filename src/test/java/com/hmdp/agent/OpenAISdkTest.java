package com.hmdp.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** 真 SDK 序列化 + HTTP + 反序列化；仅连接本地假服务，不消耗 OpenAI 配额。 */
class OpenAISdkTest {
    @Test void officialSdkWorksWithManagedDependencies() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            assertEquals("/v1/chat/completions", exchange.getRequestURI().getPath());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("{\"id\":\"chatcmpl-test\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"test-model\","
                    + "\"choices\":[{\"index\":0,\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\",\"content\":null,"
                    + "\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"GetShop\",\"arguments\":\"{\\\"shopId\\\":1}\"}}]}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        OpenAIClient client = OpenAIOkHttpClient.builder().apiKey("local-test-only")
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                .timeout(Duration.ofSeconds(5)).maxRetries(0).build();
        try {
            var response = client.chat().completions().create(ChatCompletionCreateParams.builder()
                    .model("test-model").addUserMessage("查询店铺1").addTool(MerchantTools.GetShop.class).build());
            assertEquals("GetShop", response.choices().get(0).message().toolCalls().orElseThrow().get(0).asFunction().function().name());
            JsonNode request = new ObjectMapper().readTree(body.get());
            assertEquals("test-model", request.at("/model").asText());
            assertEquals("GetShop", request.at("/tools/0/function/name").asText());
            assertTrue(request.at("/tools/0/function/strict").asBoolean());
            assertFalse(request.at("/tools/0/function/parameters/additionalProperties").asBoolean(true));
        } finally { client.close(); server.stop(0); }
    }
}
