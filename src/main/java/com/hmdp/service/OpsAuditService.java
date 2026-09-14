package com.hmdp.service;

import com.hmdp.ops.OpsProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import javax.annotation.PostConstruct;
import java.util.*;

/** 受控运维持久化服务；不存原始消息、提示词、凭据或异常文本。 */
@Service
public class OpsAuditService {
    private final JdbcTemplate db;
    private final OpsProperties properties;
    public OpsAuditService(JdbcTemplate db, OpsProperties properties) { this.db=db; this.properties=properties; }
    @PostConstruct public void initialize() {
        if(properties.isEnabled()) {
            new ResourceDatabasePopulator(new ClassPathResource("db/ops.sql")).execute(db.getDataSource());
            if(db.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='hmdp_ops_event' AND column_name='environment'",Integer.class)==0)
                db.execute("ALTER TABLE hmdp_ops_event ADD COLUMN environment VARCHAR(64) NOT NULL DEFAULT 'local'");
        }
    }
    public void event(String correlation, String outcome, String code, String action, Long order) {
        if (!properties.isEnabled()) return;
        db.update("INSERT INTO hmdp_ops_event(id,environment,correlation_id,outcome,code,action_id,order_id) VALUES(?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),properties.getEnvironment(),correlation,outcome,code,action,order);
    }
    public List<Map<String,Object>> recent() {
        return db.queryForList("SELECT id,created_at,correlation_id,outcome,code FROM hmdp_ops_event WHERE environment=? AND created_at > DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 15 MINUTE) ORDER BY created_at DESC LIMIT 30",properties.getEnvironment());
    }
}
