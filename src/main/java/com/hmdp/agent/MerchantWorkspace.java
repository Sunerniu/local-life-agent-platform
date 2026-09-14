package com.hmdp.agent;

import com.hmdp.service.IShopService;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class MerchantWorkspace {
    public record ShopOption(Long id, String name, String openHours, boolean canRead, boolean canWrite) { }
    public record Context(boolean configured, String message, List<ShopOption> shops,
                          List<ToolRuntime.ApprovalView> approvals) { }
    private final AgentProperties properties;
    private final IShopService shops;
    private final AgentPermissionService permissions;
    private final ToolRuntime runtime;
    public MerchantWorkspace(AgentProperties properties, IShopService shops, AgentPermissionService permissions, ToolRuntime runtime) {
        this.properties = properties; this.shops = shops; this.permissions = permissions; this.runtime = runtime;
    }
    public Context context(long userId) {
        AgentProperties.Grant grant = properties.getGrants().get(userId);
        List<Long> ids = grant == null ? List.of() : grant.getShopIds().stream()
                .filter(id -> permissions.allows(userId, id, "shop:read") || permissions.allows(userId, id, "shop:write"))
                .sorted().limit(100).toList();
        List<ShopOption> options = ids.isEmpty() ? List.of() : shops.listByIds(ids).stream()
                .map(shop -> new ShopOption(shop.getId(), shop.getName(), shop.getOpenHours(),
                        permissions.allows(userId, shop.getId(), "shop:read"), permissions.allows(userId, shop.getId(), "shop:write"))).toList();
        boolean configured = properties.isEnabled() && !properties.getModel().isBlank();
        return new Context(configured, configured ? "模型已配置，实际请求以返回结果为准" : "管理员尚未接入模型服务。完成服务端配置后，可在此重新检查。",
                options, runtime.listApprovals(userId));
    }
}
