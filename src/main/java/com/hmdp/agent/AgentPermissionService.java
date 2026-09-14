package com.hmdp.agent;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AgentPermissionService {
    private final AgentProperties properties;
    public AgentPermissionService(AgentProperties properties) { this.properties = properties; }

    public void require(long userId, long shopId, String permission) {
        if (!allows(userId, shopId, permission)) {
            throw new AgentException(HttpStatus.FORBIDDEN, "没有该店铺的 " + permission + " 权限");
        }
    }

    public boolean allows(long userId, long shopId, String permission) {
        AgentProperties.Grant grant = properties.getGrants().get(userId);
        return userId > 0 && shopId > 0 && grant != null && grant.getShopIds().contains(shopId)
                && grant.getPermissions().contains(permission);
    }
}
