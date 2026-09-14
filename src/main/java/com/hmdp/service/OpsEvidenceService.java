package com.hmdp.service;

import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.util.*;
import java.time.Instant;

@Service
public class OpsEvidenceService {
    private final MqOperationsService mq;
    private final OpsAuditService audit;
    private final DataSource dataSource;
    public OpsEvidenceService(MqOperationsService mq, OpsAuditService audit, DataSource dataSource) {
        this.mq=mq; this.audit=audit; this.dataSource=dataSource;
    }
    public boolean databaseHealthy() {
        try(var connection=dataSource.getConnection()) { return connection.isValid(2); }
        catch(Exception ex) { return false; }
    }
    public Map<String,Object> collect() {
        var main=mq.state(false); var dead=mq.state(true); boolean database=databaseHealthy();
        List<Map<String,Object>> events;
        boolean logsAvailable=true;
        try { events=audit.recent(); } catch(Exception ex) { events=List.of(); logsAvailable=false; }
        List<String> hypotheses=new ArrayList<>();
        if(!main.available()) hypotheses.add("E1：主队列探测失败，可能是连接、权限或队列不存在；不能直接认定 broker 宕机。");
        else if(main.consumers()==0) hypotheses.add("E1：没有消费者；优先检查应用存活和监听容器配置。");
        else if(main.ready()>0) hypotheses.add("E1：存在待消费消息，但单次快照不足以证明持续堆积或处理能力不足。");
        if(!database) hypotheses.add("E3：数据库健康探测失败，可能影响订单消费；需要进一步核对连接池及数据库状态。");
        if(dead.available() && dead.ready()>0) hypotheses.add("E2：DLQ 存在消息，需先核对消费失败原因，不能仅凭消息数量认定可以重放。");
        if(events.stream().anyMatch(e->"FAILED".equals(e.get("outcome")))) hypotheses.add("E4：近15分钟存在消费失败事件，需结合 correlation_id 排查；当前未保存原始异常文本。");
        if(hypotheses.isEmpty()) hypotheses.add("当前有限观测未发现明显异常，不等于系统没有故障。");
        return Map.of("observedAt",Instant.now().toString(),"E1_queue",main,"E2_dlq",dead,
                "E3_database",Map.of("healthy",database,"probe","JDBC isValid(2)，仅证明本次连接可用"),
                "E4_logs",Map.of("available",logsAvailable,"windowMinutes",15,"events",events),
                "hypotheses",hypotheses,
                "missing",List.of("尚未接入分布式 Trace；correlation_id 只是本次消费关联号，不冒充 Trace ID。",
                    "未采集 unacked、历史速率、P99 或完整日志；不存在这些证据时不推断确定根因。"),
                "suggestions",List.of("先核对消费者和数据库状态，再确认故障已修复。","有幂等保障且人工确认后，最多重放5条、每秒1条。","重放后检查消费审计和新诊断；发布确认并不代表业务成功。"));
    }
}
