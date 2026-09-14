package com.hmdp.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class OpsActionStore {
    private final JdbcTemplate db;
    public OpsActionStore(JdbcTemplate db) { this.db=db; }
    public void incident(String id,long user,String env,String report) {
        db.update("INSERT INTO hmdp_ops_incident(id,user_id,environment,report) VALUES(?,?,?,?)",id,user,env,report);
    }
    public Map<String,Object> incident(String id,long user,String env) {
        var rows=db.queryForList("SELECT * FROM hmdp_ops_incident WHERE id=? AND user_id=? AND environment=? AND created_at > DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 10 MINUTE)",id,user,env);
        if(rows.isEmpty()) throw new IllegalArgumentException("诊断不存在或超过10分钟，请重新诊断"); return rows.get(0);
    }
    public void propose(String id,String incident,long user,String env,int count) {
        db.update("INSERT INTO hmdp_ops_action(id,incident_id,user_id,environment,batch_size,status,expires_at) VALUES(?,?,?,?,?,'PENDING',DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 10 MINUTE))",id,incident,user,env,count);
    }
    public List<Map<String,Object>> incidents(long user,String env) {
        return db.queryForList("SELECT id,created_at FROM hmdp_ops_incident WHERE user_id=? AND environment=? ORDER BY created_at DESC LIMIT 30",user,env);
    }
    public String report(String id,long user,String env) {
        var rows=db.queryForList("SELECT report FROM hmdp_ops_incident WHERE id=? AND user_id=? AND environment=?",id,user,env);
        if(rows.isEmpty()) throw new IllegalArgumentException("诊断不存在"); return rows.get(0).get("report").toString();
    }
    public List<Map<String,Object>> list(long user,String env) {
        return db.queryForList("SELECT id,incident_id,batch_size,CASE WHEN status='PENDING' AND expires_at<=CURRENT_TIMESTAMP THEN 'EXPIRED' ELSE status END AS status,expires_at,created_at,result,verification FROM hmdp_ops_action WHERE user_id=? AND environment=? ORDER BY created_at DESC LIMIT 30",user,env);
    }
    public Map<String,Object> owned(String id,long user,String env) {
        var rows=db.queryForList("SELECT * FROM hmdp_ops_action WHERE id=? AND user_id=? AND environment=?",id,user,env);
        if(rows.isEmpty()) throw new IllegalArgumentException("审批不存在"); return rows.get(0);
    }
    @Transactional
    public boolean claim(String id,long user,String env,boolean approve) {
        if(approve && db.update("UPDATE hmdp_ops_lock SET action_id=? WHERE resource='seckill-dlq' AND action_id IS NULL",id)!=1)
            throw new IllegalArgumentException("已有重放执行中或结果待核对，禁止并行重放");
        int updated=db.update("UPDATE hmdp_ops_action SET status=?,decided_by=? WHERE id=? AND user_id=? AND environment=? AND status='PENDING' AND expires_at>CURRENT_TIMESTAMP",
                approve?"EXECUTING":"REJECTED",user,id,user,env);
        if(approve && updated==0) db.update("UPDATE hmdp_ops_lock SET action_id=NULL WHERE resource='seckill-dlq' AND action_id=?",id);
        return updated==1;
    }
    @Transactional public void finish(String id,String status,String result,boolean release) {
        db.update("UPDATE hmdp_ops_action SET status=?,result=? WHERE id=? AND status='EXECUTING'",status,result,id);
        if(release) db.update("UPDATE hmdp_ops_lock SET action_id=NULL WHERE resource='seckill-dlq' AND action_id=?",id);
    }
    public void item(String id,int sequence,long order) {
        db.update("INSERT INTO hmdp_ops_replay_item(action_id,sequence_no,order_id,status) VALUES(?,?,?,'PUBLISHING')",id,sequence,order);
    }
    public void sent(String id,int sequence) {
        db.update("UPDATE hmdp_ops_replay_item SET status='PUBLISHED' WHERE action_id=? AND sequence_no=?",id,sequence);
    }
    public Map<String,Object> verification(String id) {
        var items=db.queryForList("SELECT sequence_no,order_id,status FROM hmdp_ops_replay_item WHERE action_id=? ORDER BY sequence_no",id);
        long confirmed=db.queryForObject("SELECT COUNT(*) FROM hmdp_ops_replay_item i WHERE action_id=? AND EXISTS (SELECT 1 FROM hmdp_ops_event e WHERE e.action_id=i.action_id AND e.order_id=i.order_id AND e.outcome IN ('CONSUMED','DUPLICATE'))",Long.class,id);
        long failed=db.queryForObject("SELECT COUNT(*) FROM hmdp_ops_event WHERE action_id=? AND outcome='FAILED'",Long.class,id);
        return Map.of("items",items,"businessConfirmed",confirmed,"failedEvents",failed,
                "message", "投递确认不等于业务成功；仅消费审计中的 CONSUMED/DUPLICATE 计入业务确认。无记录表示尚未确认。");
    }
    public void verified(String id,String report) { db.update("UPDATE hmdp_ops_action SET verification=? WHERE id=?",report,id); }
}
