package com.hmdp.ops;

import com.hmdp.service.*;
import com.hmdp.entity.VoucherOrder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 仅显式开启；仅使用本测试新建的随机ID，审批测试事务回滚，不触碰现有消息。 */
@EnabledIfEnvironmentVariable(named="RUN_LOCAL_INTEGRATION",matches="true")
@SpringBootTest(properties={"spring.rabbitmq.listener.simple.auto-startup=false","hmdp.agent.enabled=false","hmdp.ops.enabled=true"})
class OpsPersistenceIntegrationTest {
    @Autowired OpsActionStore store;
    @Autowired JdbcTemplate db;
    @Autowired IVoucherOrderService orders;
    @Test @Transactional void approvalSurvivesServiceRecreationAndCannotExecuteTwice() {
        String incident=UUID.randomUUID().toString(),action=UUID.randomUUID().toString();
        store.incident(incident,7,"test","{}");store.propose(action,incident,7,"test",1);
        var recreated=new OpsActionStore(db);
        assertEquals("PENDING",recreated.owned(action,7,"test").get("status"));
        assertThrows(IllegalArgumentException.class,()->recreated.owned(action,8,"test"));
        assertThrows(IllegalArgumentException.class,()->recreated.owned(action,7,"other-environment"));
        assertTrue(store.claim(action,7,"test",true));
        store.finish(action,"PUBLISHED","{}",true);
        assertFalse(store.claim(action,7,"test",true));
        assertEquals("PUBLISHED",recreated.owned(action,7,"test").get("status"));
    }
    @Test @Transactional void expiredAndRejectedCannotBeClaimed() {
        String incident=UUID.randomUUID().toString(),action=UUID.randomUUID().toString();
        store.incident(incident,7,"test","{}");store.propose(action,incident,7,"test",1);
        db.update("UPDATE hmdp_ops_action SET expires_at=DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 SECOND) WHERE id=?",action);
        assertFalse(store.claim(action,7,"test",true));
        assertEquals("EXPIRED",store.list(7,"test").stream().filter(a->a.get("id").equals(action)).findFirst().orElseThrow().get("status"));
    }
    @Test void concurrentRedeliveryOnlyCreatesOneOrderAndDebitsOnce() throws Exception {
        long voucher=(UUID.randomUUID().getMostSignificantBits() & 0x1fffffffffffffffL)+100;
        long order=voucher+1,user=voucher+2;
        db.update("INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time) VALUES(?,2,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",voucher);
        var executor=Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start=new CountDownLatch(1);
            Callable<String> consume=()->{start.await();return orders.consumeOrder(new VoucherOrder().setId(order).setUserId(user).setVoucherId(voucher));};
            var first=executor.submit(consume);var second=executor.submit(consume);start.countDown();
            assertEquals(Set.of("CONSUMED","DUPLICATE"),Set.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS)));
            assertEquals(1,db.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?",Integer.class,voucher));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE id=?",Integer.class,order));
        } finally {
            executor.shutdown();assertTrue(executor.awaitTermination(30,TimeUnit.SECONDS));
            db.update("DELETE FROM tb_voucher_order WHERE id=? AND voucher_id=? AND user_id=?",order,voucher,user);
            db.update("DELETE FROM tb_seckill_voucher WHERE voucher_id=?",voucher);
        }
    }
}
