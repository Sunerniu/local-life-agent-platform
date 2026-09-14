package com.hmdp.agent;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/agent/merchant")
public class MerchantAgentController {
    public record ChatRequest(String message, Long shopId) { }
    public record DecisionRequest(Boolean approve) { }
    private final MerchantAgent agent;
    private final ToolRuntime runtime;
    private final MerchantWorkspace workspace;
    private final AgentPermissionService permissions;
    public MerchantAgentController(MerchantAgent agent, ToolRuntime runtime, MerchantWorkspace workspace, AgentPermissionService permissions) {
        this.agent = agent; this.runtime = runtime; this.workspace = workspace; this.permissions = permissions;
    }
    @GetMapping("/context")
    public Result context() { return Result.ok(workspace.context(userId())); }
    @PostMapping("/chat")
    public Result chat(@RequestBody ChatRequest request) {
        long userId = userId();
        if (request.shopId() == null || (!permissions.allows(userId, request.shopId(), "shop:read")
                && !permissions.allows(userId, request.shopId(), "shop:write"))) {
            throw new AgentException(HttpStatus.FORBIDDEN, "请选择已授权店铺");
        }
        return Result.ok(agent.chat(userId, request.message(), request.shopId()));
    }
    @GetMapping("/approvals/{id}")
    public Result approval(@PathVariable UUID id) { return Result.ok(runtime.getApproval(userId(), id)); }
    @PostMapping("/approvals/{id}/decision")
    public Result decide(@PathVariable UUID id, @RequestBody DecisionRequest request) {
        long userId = userId();
        if (request.approve() == null) throw new AgentException(HttpStatus.BAD_REQUEST, "必须明确提供 approve 布尔值");
        return Result.ok(runtime.decide(userId, id, request.approve()));
    }
    private long userId() {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) throw new AgentException(HttpStatus.UNAUTHORIZED, "请先登录");
        return user.getId();
    }
}
