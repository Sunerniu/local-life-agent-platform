package com.hmdp.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.chat.completions.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MerchantAgentTest {
    final MerchantModel model = mock(MerchantModel.class);
    final ToolRuntime runtime = mock(ToolRuntime.class);
    final AgentProperties properties = new AgentProperties();
    MerchantAgent agent() {
        properties.setEnabled(true); properties.setModel("test-model");
        return new MerchantAgent(model, runtime, properties, new ObjectMapper());
    }
    ChatCompletionMessage call(String tool, String args) {
        return ChatCompletionMessage.builder().content( java.util.Optional.empty()).refusal(java.util.Optional.empty()).addToolCall(ChatCompletionMessageFunctionToolCall.builder()
                .id("call_1").function(ChatCompletionMessageFunctionToolCall.Function.builder().name(tool).arguments(args).build()).build()).build();
    }
    @Test void functionResultIsSentBackToModel() {
        when(model.complete(any())).thenReturn(call("GetShop", "{\"shopId\":1}"),
                ChatCompletionMessage.builder().content("店铺资料已查询").refusal(java.util.Optional.empty()).build());
        when(runtime.execute(7, "GetShop", "{\"shopId\":1}")).thenReturn(new ToolRuntime.ToolResult("COMPLETED", "shop-data"));
        assertEquals("COMPLETED", agent().chat(7, "查询店铺1").status());
        ArgumentCaptor<ChatCompletionCreateParams> requests = ArgumentCaptor.forClass(ChatCompletionCreateParams.class);
        verify(model, times(2)).complete(requests.capture());
        assertTrue(requests.getAllValues().get(1).messages().toString().contains("shop-data"));
        assertEquals(3, requests.getAllValues().get(0).tools().orElseThrow().size());
    }
    @Test void writeStopsForHumanApproval() {
        var approval = new ToolRuntime.ApprovalView(UUID.randomUUID(), "UpdateShopHours", 1, "10:00-22:00", Instant.now().plusSeconds(600), "PENDING", null, "测试店铺", "08:00-20:00");
        when(model.complete(any())).thenReturn(call("UpdateShopHours", "{}"));
        when(runtime.execute(7, "UpdateShopHours", "{}")).thenReturn(new ToolRuntime.ToolResult("PENDING_APPROVAL", approval));
        assertEquals(List.of(approval), agent().chat(7, "修改营业时间").approvals());
        verify(model, times(1)).complete(any());
        verify(runtime, never()).decide(anyLong(), any(), anyBoolean());
    }
    @Test void loopAndToolBudgetAreBounded() {
        properties.setMaxToolCalls(1);
        when(model.complete(any())).thenReturn(call("GetShop", "{}"));
        when(runtime.execute(anyLong(), anyString(), anyString())).thenReturn(new ToolRuntime.ToolResult("COMPLETED", "x"));
        assertEquals("LIMIT_REACHED", agent().chat(7, "循环查询").status());
        verify(runtime, times(1)).execute(anyLong(), anyString(), anyString());
    }
    @Test void requiresAuthenticatedIdentityAndBoundedInput() {
        MerchantAgent agent = agent();
        assertThrows(AgentException.class, () -> agent.chat(0, "hello"));
        assertThrows(AgentException.class, () -> agent.chat(7, "x".repeat(4001)));
        verifyNoInteractions(model, runtime);
    }
}
