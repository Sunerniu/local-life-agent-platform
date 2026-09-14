package com.hmdp.agent;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MerchantWorkspaceTest {
    @Test void contextContainsOnlyAuthorizedShopsAndSafeConfigurationStatus() {
        AgentProperties properties = new AgentProperties();
        AgentProperties.Grant grant = new AgentProperties.Grant();
        grant.setShopIds(Set.of(1L)); grant.setPermissions(Set.of("shop:read")); properties.getGrants().put(7L,grant);
        IShopService shops = mock(IShopService.class); ToolRuntime runtime = mock(ToolRuntime.class);
        when(shops.listByIds(List.of(1L))).thenReturn(List.of(new Shop().setId(1L).setName("店铺1")));
        when(runtime.listApprovals(anyLong())).thenReturn(List.of());
        MerchantWorkspace workspace = new MerchantWorkspace(properties,shops,new AgentPermissionService(properties),runtime);
        var context = workspace.context(7);
        assertFalse(context.configured()); assertEquals(1,context.shops().size());
        assertTrue(context.shops().get(0).canRead()); assertFalse(context.shops().get(0).canWrite());
        assertTrue(workspace.context(8).shops().isEmpty());
        verify(shops,times(1)).listByIds(anyCollection());
    }
}
