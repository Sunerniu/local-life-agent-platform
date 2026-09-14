package com.hmdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.AgentException;
import com.hmdp.config.RabbitMQTopicConfig;
import com.hmdp.ops.OpsProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.Semaphore;

/** 工具只能调用本 Service；权限、固定资源、审计和执行均在服务端。 */
@Service
public class OpsWorkflowService {
    private final OpsProperties properties;
    private final OpsEvidenceService evidence;
    private final OpsActionStore store;
    private final MqOperationsService mq;
    private final OrderMessageService parser;
    private final ObjectMapper json;
    private final Semaphore diagnostics=new Semaphore(2);
    public OpsWorkflowService(OpsProperties properties, OpsEvidenceService evidence, OpsActionStore store,
                              MqOperationsService mq, OrderMessageService parser, ObjectMapper json) {
        this.properties=properties; this.evidence=evidence; this.store=store; this.mq=mq; this.parser=parser; this.json=json;
    }
    public void require(long user, boolean write) {
        if(!properties.isEnabled()) throw new AgentException(HttpStatus.SERVICE_UNAVAILABLE,"运维模块尚未启用");
        if(user<=0 || !properties.getDiagnosticians().contains(user) || (write && !properties.getOperators().contains(user)))
            throw new AgentException(HttpStatus.FORBIDDEN,"没有该环境的运维权限；商家权限不等于运维权限");
    }
    public Map<String,Object> context(long user) {
        require(user,false);
        return Map.of("environment",properties.getEnvironment(),"canOperate",properties.getOperators().contains(user),
                "source",RabbitMQTopicConfig.DLQ,"destination",RabbitMQTopicConfig.QUEUE,"actions",store.list(user,properties.getEnvironment()),
                "incidents",store.incidents(user,properties.getEnvironment()));
    }
    public Map<String,Object> report(long user,String id) {
        require(user,false); id=uuid(id);
        try { return Map.of("incidentId",id,"environment",properties.getEnvironment(),"evidence",json.readTree(store.report(id,user,properties.getEnvironment()))); }
        catch(java.io.IOException ex) { throw new IllegalStateException("报告读取失败"); }
    }
    public Map<String,Object> diagnose(long user) {
        require(user,false);
        if(!diagnostics.tryAcquire()) throw new AgentException(HttpStatus.TOO_MANY_REQUESTS,"诊断繁忙，请稍后重试");
        try {
            String id=UUID.randomUUID().toString(); var report=evidence.collect();
            store.incident(id,user,properties.getEnvironment(),encode(report));
            return Map.of("incidentId",id,"environment",properties.getEnvironment(),"evidence",report);
        } finally { diagnostics.release(); }
    }
    public Map<String,Object> propose(long user,String incident,int count) {
        require(user,true);
        if(count<1 || count>5) throw new IllegalArgumentException("批量必须为1至5条");
        store.incident(uuid(incident),user,properties.getEnvironment());
        if(store.list(user,properties.getEnvironment()).stream().filter(a->"PENDING".equals(a.get("status"))).count()>=5)
            throw new IllegalArgumentException("请先处理已有待审批操作");
        preflight();
        String id=UUID.randomUUID().toString(); store.propose(id,incident,user,properties.getEnvironment(),count);
        return Map.of("actionId",id,"status","PENDING","maxMessages",count,"ratePerSecond",1,
                "source",RabbitMQTopicConfig.DLQ,"destination",RabbitMQTopicConfig.QUEUE,
                "risk","取执行时队首最多指定条数（并非预览固定消息）；可能产生订单、扣减库存，不能自动撤销。请先确认故障已修复。10分钟内有效。");
    }
    private void preflight() {
        var main=mq.state(false); var dead=mq.state(true);
        if(!main.available() || main.consumers()<1 || !dead.available() || dead.consumers()!=0 || !evidence.databaseHealthy())
            throw new IllegalArgumentException("执行条件不满足：主队列需有消费者、DLQ不可有其他消费者、数据库与队列须可用");
    }
    public Map<String,Object> decide(long user,String id,boolean approve,boolean repaired) {
        require(user,true); id=uuid(id);
        var current=store.owned(id,user,properties.getEnvironment());
        if(!"PENDING".equals(current.get("status"))) return current;
        if(approve) {
            if(!repaired) throw new IllegalArgumentException("必须由人明确确认故障已修复，并接受重放影响");
            store.incident(current.get("incident_id").toString(),user,properties.getEnvironment());
            preflight();
        }
        if(!store.claim(id,user,properties.getEnvironment(),approve)) throw new IllegalArgumentException("审批已过期或已被处理");
        if(approve) replay(user,id,((Number)current.get("batch_size")).intValue());
        return store.owned(id,user,properties.getEnvironment());
    }
    private void replay(long user,String id,int count) {
        int sent=0;
        try(var channel=mq.channel()) {
            for(int i=0;i<count;i++) {
                require(user,true); preflight();
                var message=channel.basicGet(RabbitMQTopicConfig.DLQ,false);
                if(message==null) break;
                try {
                    var order=parser.parse(message.getBody());
                    store.item(id,i,order.getId());
                    mq.publishConfirmed(RabbitMQTopicConfig.QUEUE,message.getBody(),Map.of("ops-action-id",id));
                    store.sent(id,i);
                    channel.basicAck(message.getEnvelope().getDeliveryTag(),false);
                } catch(Exception ex) {
                    // Spring 缓存 channel 的 close 不一定关闭物理通道，必须显式归还未确认消息。
                    try { channel.basicNack(message.getEnvelope().getDeliveryTag(),false,true); } catch(Exception ignored) { channel.abort(); }
                    throw ex;
                }
                sent++;
                if(i+1<count) Thread.sleep(1000);
            }
            store.finish(id,"PUBLISHED",encode(Map.of("published",sent,"message","投递完成，业务结果请执行验证；不能自动撤销")),true);
        } catch(Exception ex) {
            if(ex instanceof InterruptedException) Thread.currentThread().interrupt();
            store.finish(id,"UNCERTAIN",encode(Map.of("publishedConfirmed",sent,"message","执行中断，源消息可能重入队；禁止自动重试。全局重放锁保留，需管理员核对消息与订单。")),false);
        }
    }
    public Map<String,Object> verify(long user,String id) {
        require(user,false); id=uuid(id); store.owned(id,user,properties.getEnvironment());
        Map<String,Object> report=Map.of("consumption",store.verification(id),"currentEvidence",evidence.collect());
        store.verified(id,encode(report)); return report;
    }
    private String uuid(String id) { return UUID.fromString(id).toString(); }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch(Exception ex) { throw new IllegalStateException("审计序列化失败"); } }
}
