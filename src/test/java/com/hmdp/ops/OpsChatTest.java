package com.hmdp.ops;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.*;
import com.hmdp.service.OpsWorkflowService;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class OpsChatTest {
    private com.openai.models.chat.completions.ChatCompletionMessage call(String name,String args) {
        return com.openai.models.chat.completions.ChatCompletionMessage.builder().content(java.util.Optional.empty()).refusal(java.util.Optional.empty())
            .addToolCall(com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall.builder().id("test")
                .function(com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall.Function.builder().name(name).arguments(args).build()).build()).build();
    }
    @Test void modelCanDiagnoseButNeverApproveOrReadRawInfrastructure() {
        var model=mock(MerchantModel.class);var workflow=mock(OpsWorkflowService.class);var p=new AgentProperties();p.setEnabled(true);p.setModel("test");
        when(workflow.diagnose(7)).thenReturn(java.util.Map.of("incidentId","example"));
        when(model.complete(any())).thenReturn(call("DiagnoseMq","{\"scope\":\"SECKILL_QUEUE\"}"));
        var chat=new OpsChat(model,p,workflow,new ObjectMapper());
        assertEquals("diagnosis",chat.chat(7,"诊断",null).get("kind"));
        when(model.complete(any())).thenReturn(call("ApproveReplay","{}"));
        assertThrows(IllegalArgumentException.class,()->chat.chat(7,"执行",null));
        verify(workflow,never()).decide(anyLong(),anyString(),anyBoolean(),anyBoolean());
    }
    @Test void modelCannotSubstituteIncidentOrAddHiddenParameters() {
        var model=mock(MerchantModel.class);var workflow=mock(OpsWorkflowService.class);var p=new AgentProperties();p.setEnabled(true);p.setModel("test");
        var chat=new OpsChat(model,p,workflow,new ObjectMapper());
        String id=java.util.UUID.randomUUID().toString();
        when(model.complete(any())).thenReturn(call("ProposeDlqReplay","{\"incidentId\":\"other\",\"count\":1}"));
        assertThrows(IllegalArgumentException.class,()->chat.chat(7,"申请",id));
        when(model.complete(any())).thenReturn(call("DiagnoseMq","{\"queue\":\"other\"}"));
        assertThrows(IllegalArgumentException.class,()->chat.chat(7,"诊断",null));
        verify(workflow,never()).propose(anyLong(),anyString(),anyInt());
    }
    @Test void disabledModelDoesNotDisableDirectDiagnostics() {
        var model=mock(MerchantModel.class);var workflow=mock(OpsWorkflowService.class);
        var chat=new OpsChat(model,new AgentProperties(),workflow,new ObjectMapper());
        assertThrows(IllegalArgumentException.class,()->chat.chat(7,"诊断",null));verifyNoInteractions(model);
        verify(workflow).require(7,false);
    }
}
