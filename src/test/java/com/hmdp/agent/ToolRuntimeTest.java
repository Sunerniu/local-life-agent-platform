package com.hmdp.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.Set;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToolRuntimeTest {
    IShopService shops;
    IVoucherService vouchers;
    AgentProperties properties;
    AgentProperties.Grant grant;
    ToolRuntime runtime;
    Clock clock;
    final String update = "{\"shopId\":1,\"openHours\":\"10:00-22:00\"}";

    @BeforeEach void setup() {
        shops = mock(IShopService.class); vouchers = mock(IVoucherService.class);
        when(shops.getById(1L)).thenReturn(new Shop().setId(1L).setName("测试店铺").setOpenHours("08:00-20:00"));
        properties = new AgentProperties(); grant = new AgentProperties.Grant();
        grant.setShopIds(Set.of(1L)); grant.setPermissions(Set.of("shop:read", "shop:write"));
        properties.getGrants().put(7L, grant);
        clock = mock(Clock.class);
        when(clock.instant()).thenReturn(Instant.parse("2026-09-07T00:00:00Z"));
        runtime = new ToolRuntime(shops, vouchers, new AgentPermissionService(properties), properties, new ObjectMapper(), clock);
    }
    ToolRuntime.ApprovalView pending() {
        return (ToolRuntime.ApprovalView) runtime.execute(7, "UpdateShopHours", update).data();
    }
    @Test void queriesReuseServices() {
        when(shops.queryById(1L)).thenReturn(Result.ok("shop"));
        when(vouchers.queryVoucherOfShop(1L)).thenReturn(Result.ok());
        assertEquals("COMPLETED", runtime.execute(7, "GetShop", "{\"shopId\":1}").status());
        runtime.execute(7, "GetShopVouchers", "{\"shopId\":1}");
        verify(shops).queryById(1L); verify(vouchers).queryVoucherOfShop(1L);
    }
    @Test void deniesUnknownUserAndCrossShop() {
        assertThrows(AgentException.class, () -> runtime.execute(8, "GetShop", "{\"shopId\":1}"));
        assertThrows(AgentException.class, () -> runtime.execute(7, "GetShop", "{\"shopId\":2}"));
        verifyNoInteractions(shops, vouchers);
    }

    @Test void toolCannotSwitchAwayFromSelectedShop() {
        assertThrows(AgentException.class, () -> runtime.execute(7, 2L, "GetShop", "{\"shopId\":1}"));
        verifyNoInteractions(shops, vouchers);
    }

    @Test void changedSnapshotIsReportedAsFailureAndNotRetried() {
        var approval = pending();
        when(shops.updateHoursIfUnchanged(1L,"08:00-20:00","10:00-22:00"))
                .thenReturn(Result.fail("店铺营业时间已变化"));
        assertEquals("FAILED",runtime.decide(7,approval.id(),true).status());
        assertEquals("FAILED",runtime.decide(7,approval.id(),true).status());
        verify(shops,times(1)).updateHoursIfUnchanged(1L,"08:00-20:00","10:00-22:00");
    }

    @Test void approvalListIsScopedAndShowsExpiredPendingItems() {
        var approval = pending();
        assertEquals(1,runtime.listApprovals(7).size());
        assertTrue(runtime.listApprovals(8).isEmpty());
        when(clock.instant()).thenReturn(approval.expiresAt());
        assertEquals("EXPIRED",runtime.listApprovals(7).get(0).status());
        grant.setShopIds(Set.of());
        assertTrue(runtime.listApprovals(7).isEmpty());
    }
    @Test void rejectsUnknownToolsSpoofingAndInvalidArguments() {
        assertThrows(AgentException.class, () -> runtime.execute(7, "sql", "{}"));
        for (String args : new String[]{"{}", "[]", "{\"shopId\":1.2}", "{\"shopId\":-1}",
                "{\"shopId\":1,\"userId\":7}", "{\"shopId\":1} {}", "{\"shopId\":1,\"shopId\":2}"}) {
            assertThrows(AgentException.class, () -> runtime.execute(7, "GetShop", args));
        }
        assertThrows(AgentException.class, () -> runtime.execute(7, "UpdateShopHours", "{\"shopId\":1,\"openHours\":\"25:00-22:00\"}"));
        verifyNoInteractions(shops, vouchers);
    }
    @Test void noWriteUntilApprovalAndReplayDoesNotWriteTwice() {
        ToolRuntime.ApprovalView approval = pending();
        verify(shops, never()).updateHoursIfUnchanged(anyLong(), anyString(), anyString());
        when(shops.updateHoursIfUnchanged(anyLong(), anyString(), anyString())).thenReturn(Result.ok());
        assertEquals("EXECUTED", runtime.decide(7, approval.id(), true).status());
        assertEquals("EXECUTED", runtime.decide(7, approval.id(), true).status());
        verify(shops, times(1)).updateHoursIfUnchanged(1L, "08:00-20:00", "10:00-22:00");
        assertEquals("测试店铺", approval.shopName());
        assertEquals("08:00-20:00", approval.beforeOpenHours());
    }
    @Test void rejectsOtherApproverAndRevokedPermissions() {
        ToolRuntime.ApprovalView approval = pending();
        assertThrows(AgentException.class, () -> runtime.getApproval(8, approval.id()));
        assertThrows(AgentException.class, () -> runtime.decide(8, approval.id(), true));
        grant.setPermissions(Set.of("shop:read"));
        assertThrows(AgentException.class, () -> runtime.decide(7, approval.id(), true));
        verify(shops, never()).updateHoursIfUnchanged(anyLong(), anyString(), anyString());
    }
    @Test void rejectionAndExpiryNeverWrite() {
        ToolRuntime.ApprovalView approval = pending();
        assertEquals("REJECTED", runtime.decide(7, approval.id(), false).status());
        assertEquals("REJECTED", runtime.decide(7, approval.id(), true).status());
        ToolRuntime.ApprovalView expiring = pending();
        when(clock.instant()).thenReturn(approval.expiresAt());
        assertThrows(AgentException.class, () -> runtime.decide(7, expiring.id(), true));
        verify(shops, never()).updateHoursIfUnchanged(anyLong(), anyString(), anyString());
    }
    @Test void failedWriteIsNotAutomaticallyRetried() {
        ToolRuntime.ApprovalView approval = pending();
        when(shops.updateHoursIfUnchanged(anyLong(), anyString(), anyString())).thenThrow(new IllegalStateException("database"));
        assertEquals("FAILED", runtime.decide(7, approval.id(), true).status());
        assertEquals("FAILED", runtime.decide(7, approval.id(), true).status());
        verify(shops, times(1)).updateHoursIfUnchanged(anyLong(), anyString(), anyString());
    }
    @Test void capacityIsBounded() {
        properties.setMaxApprovals(1); pending();
        assertThrows(AgentException.class, this::pending);
        verify(shops, never()).updateHoursIfUnchanged(anyLong(), anyString(), anyString());
    }
    @Test void concurrentConfirmationExecutesOnce() throws Exception {
        ToolRuntime.ApprovalView approval = pending();
        when(shops.updateHoursIfUnchanged(anyLong(), anyString(), anyString())).thenReturn(Result.ok());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(() -> runtime.decide(7, approval.id(), true));
            Future<?> b = pool.submit(() -> runtime.decide(7, approval.id(), true));
            a.get(5, TimeUnit.SECONDS); b.get(5, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        verify(shops, times(1)).updateHoursIfUnchanged(anyLong(), anyString(), anyString());
    }
}
