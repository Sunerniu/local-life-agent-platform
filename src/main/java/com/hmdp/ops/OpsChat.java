package com.hmdp.ops;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.*;
import com.hmdp.service.OpsWorkflowService;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.Semaphore;

/** 复用同一模型传输的工具路由，不启动多个 Agent，不给模型任何执行/审批工具。 */
@Service
public class OpsChat {
    @JsonClassDescription("只读采集秒杀队列、DLQ、数据库和消费事件，返回带证据诊断。")
    public enum Scope { SECKILL_QUEUE }
    public static class DiagnoseMq { public Scope scope; }
    @JsonClassDescription("申请DLQ重放，不能执行。必须提供页面已有 incidentId 和用户明确指定的1至5条数量。")
    public static class ProposeDlqReplay { public String incidentId; public Integer count; }
    private final MerchantModel model;
    private final AgentProperties properties;
    private final OpsWorkflowService workflow;
    private final ObjectMapper json;
    private final Semaphore slots=new Semaphore(2);
    public OpsChat(MerchantModel model, AgentProperties properties, OpsWorkflowService workflow, ObjectMapper json) {
        this.model=model; this.properties=properties; this.workflow=workflow; this.json=json;
    }
    public Map<String,Object> chat(long user,String message,String incident) {
        workflow.require(user,false);
        if(message==null || message.isBlank() || message.length()>2000) throw new IllegalArgumentException("消息长度需为1至2000字符");
        if(!properties.isEnabled()) throw new IllegalArgumentException("模型未启用，仍可使用页面上的只读诊断按钮");
        if(!slots.tryAcquire()) throw new IllegalArgumentException("模型繁忙，请稍后重试");
        try {
            var params=ChatCompletionCreateParams.builder().model(properties.getModel()).maxCompletionTokens(700).parallelToolCalls(false)
                .addSystemMessage("你是运维工具路由，只处理秒杀队列。诊断调用DiagnoseMq，申请重放调用ProposeDlqReplay。"
                    + "身份权限由后端判断。没有执行、确认审批工具，不能声称执行成功。缺少明确重放数量或incidentId时询问，不猜测。"
                    + "不能凭空诊断；根因和证据由工具报告提供。每次最多调用一个工具。")
                .addUserMessage("页面最近诊断ID="+(incident==null?"无":UUID.fromString(incident))+"\n"+message)
                .addTool(DiagnoseMq.class).addTool(ProposeDlqReplay.class).build();
            var response=model.complete(params); var calls=response.toolCalls().orElse(List.of());
            if(calls.isEmpty()) return Map.of("kind","text","message",response.content().orElse("请描述需要诊断的问题。"));
            if(calls.size()!=1 || !calls.get(0).isFunction()) throw new IllegalArgumentException("模型调用超出范围");
            var call=calls.get(0).asFunction().function();
            if(call.arguments().length()>2048) throw new IllegalArgumentException("工具参数过长");
            var args=json.reader().with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(call.arguments());
            if(!args.isObject()) throw new IllegalArgumentException("工具参数必须是对象");
            if(call.name().equals("DiagnoseMq") && args.size()==1 && "SECKILL_QUEUE".equals(args.path("scope").asText()))
                return Map.of("kind","diagnosis","data",workflow.diagnose(user));
            if(call.name().equals("ProposeDlqReplay") && args.size()==2 && args.path("incidentId").isTextual() && args.path("count").isInt()) {
                if(incident==null || !incident.equals(args.path("incidentId").asText())) throw new IllegalArgumentException("模型不能替换页面诊断ID");
                return Map.of("kind","proposal","data",workflow.propose(user,incident,args.path("count").asInt()));
            }
            throw new IllegalArgumentException("未知工具或不合法参数");
        } catch(java.io.IOException ex) { throw new IllegalArgumentException("模型工具参数解析失败"); }
        finally { slots.release(); }
    }
}
