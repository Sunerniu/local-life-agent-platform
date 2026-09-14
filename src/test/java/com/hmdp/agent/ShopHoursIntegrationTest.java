package com.hmdp.agent;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

/** 可选本地集成测试；数据库修改随测试事务回滚。 */
@EnabledIfEnvironmentVariable(named = "RUN_LOCAL_INTEGRATION", matches = "true")
@SpringBootTest(properties = {"spring.rabbitmq.listener.simple.auto-startup=false", "hmdp.agent.enabled=false"})
class ShopHoursIntegrationTest {
    @Autowired IShopService shops;

    @Test @Transactional
    void changedSnapshotCannotOverwriteNewerHours() {
        Shop original = shops.getById(1L);
        assertNotNull(original);
        String before = original.getOpenHours();
        String next = "09:01-20:59".equals(before) ? "09:02-20:58" : "09:01-20:59";
        assertFalse(shops.updateHoursIfUnchanged(1L, "invalid-original", next).getSuccess());
        assertEquals(before, shops.getById(1L).getOpenHours());
        assertTrue(shops.updateHoursIfUnchanged(1L, before, next).getSuccess());
        assertEquals(next, shops.getById(1L).getOpenHours());
        assertFalse(shops.updateHoursIfUnchanged(1L, before, "08:00-19:00").getSuccess());
        assertEquals(next, shops.getById(1L).getOpenHours());
    }
}
