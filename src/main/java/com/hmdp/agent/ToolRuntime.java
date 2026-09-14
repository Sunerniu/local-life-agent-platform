package com.hmdp.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** 白名单、参数校验、授权、审批均由 Java 决定，模型不能绕过。 */
@Slf4j
@Service
public class ToolRuntime {
    public record ToolResult(String status, Object data) { }
    public record ApprovalView(UUID id, String tool, long shopId, String openHours,
                               Instant expiresAt, String status, Object result,
                               String shopName, String beforeOpenHours) { }
    private static final class Approval {
        final UUID id = UUID.randomUUID();
        final long userId;
        final long shopId;
        final String hours;
        final String beforeHours;
        final String shopName;
        final Instant expiresAt;
        String status = "PENDING";
        Object result;
        Approval(long userId, long shopId, String hours, Instant expiresAt, String shopName, String beforeHours) {
            this.userId = userId; this.shopId = shopId; this.hours = hours; this.expiresAt = expiresAt;
            this.shopName = shopName; this.beforeHours = beforeHours;
        }
        ApprovalView view() {
            return new ApprovalView(id, "UpdateShopHours", shopId, hours, expiresAt, status, result, shopName, beforeHours);
        }
    }
    private final IShopService shops;
    private final IVoucherService vouchers;
    private final AgentPermissionService permissions;
    private final AgentProperties properties;
    private final ObjectMapper json;
    private final Clock clock;
    // 单实例、短期工作流状态，不保存对话或长期记忆。重启后审批失效。
    private final Map<UUID, Approval> approvals = new HashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public ToolRuntime(IShopService shops, IVoucherService vouchers, AgentPermissionService permissions,
                       AgentProperties properties, ObjectMapper json) {
        this(shops, vouchers, permissions, properties, json, Clock.systemUTC());
    }
    ToolRuntime(IShopService shops, IVoucherService vouchers, AgentPermissionService permissions,
                AgentProperties properties, ObjectMapper json, Clock clock) {
        this.shops = shops; this.vouchers = vouchers; this.permissions = permissions;
        this.properties = properties; this.json = json; this.clock = clock;
    }

    public ToolResult execute(long userId, String tool, String arguments) {
        return execute(userId, null, tool, arguments);
    }

    public ToolResult execute(long userId, Long selectedShopId, String tool, String arguments) {
        if (!Set.of("GetShop", "GetShopVouchers", "UpdateShopHours").contains(tool)) {
            throw bad("未知工具");
        }
        JsonNode args = parse(arguments);
        Set<String> allowed = tool.equals("UpdateShopHours") ? Set.of("shopId", "openHours") : Set.of("shopId");
        args.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) throw bad("不允许的工具参数"); });
        JsonNode id = args.get("shopId");
        if (id == null || !id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0) throw bad("shopId 必须为正整数");
        long shopId = id.longValue();
        if (selectedShopId != null && selectedShopId != shopId) throw bad("工具店铺与当前所选店铺不一致");
        String permission = tool.equals("UpdateShopHours") ? "shop:write" : "shop:read";
        permissions.require(userId, shopId, permission);
        log.info("agent tool user={} shop={} tool={}", userId, shopId, tool);
        return switch (tool) {
            case "GetShop" -> new ToolResult("COMPLETED", shops.queryById(shopId));
            case "GetShopVouchers" -> new ToolResult("COMPLETED", vouchers.queryVoucherOfShop(shopId));
            case "UpdateShopHours" -> {
                JsonNode hours = args.get("openHours");
                if (hours == null || !hours.isTextual() || !hours.textValue().matches(
                        "(?:[01][0-9]|2[0-3]):[0-5][0-9]-(?:[01][0-9]|2[0-3]):[0-5][0-9]")) throw bad("营业时间格式必须为 HH:mm-HH:mm");
                yield new ToolResult("PENDING_APPROVAL", createApproval(userId, shopId, hours.textValue()));
            }
            default -> throw bad("未知工具");
        };
    }

    private synchronized ApprovalView createApproval(long userId, long shopId, String hours) {
        approvals.values().removeIf(a -> !clock.instant().isBefore(a.expiresAt));
        if (approvals.size() >= properties.getMaxApprovals()) throw new AgentException(HttpStatus.TOO_MANY_REQUESTS, "审批容量已满");
        Shop shop = shops.getById(shopId);
        if (shop == null) throw new AgentException(HttpStatus.NOT_FOUND, "店铺不存在");
        Approval approval = new Approval(userId, shopId, hours,
                clock.instant().plusSeconds(properties.getApprovalTtlSeconds()), shop.getName(), shop.getOpenHours());
        approvals.put(approval.id, approval);
        log.info("agent approval created id={} user={} shop={}", approval.id, userId, shopId);
        return approval.view();
    }

    public synchronized ApprovalView getApproval(long userId, UUID id) {
        Approval approval = owned(userId, id);
        permissions.require(userId, approval.shopId, "shop:write");
        return approval.view();
    }

    public synchronized List<ApprovalView> listApprovals(long userId) {
        return approvals.values().stream().filter(a -> a.userId == userId)
                .filter(a -> permissions.allows(userId, a.shopId, "shop:write"))
                .sorted(Comparator.comparing((Approval a) -> a.expiresAt).reversed()).limit(50)
                .map(a -> !clock.instant().isBefore(a.expiresAt) && a.status.equals("PENDING")
                        ? new ApprovalView(a.id, "UpdateShopHours", a.shopId, a.hours, a.expiresAt, "EXPIRED", null, a.shopName, a.beforeHours)
                        : a.view()).toList();
    }

    // 在检查、状态迁移、执行之间持锁，重复确认只返回已有结果，不再次写入。
    public synchronized ApprovalView decide(long userId, UUID id, boolean approve) {
        Approval approval = owned(userId, id);
        permissions.require(userId, approval.shopId, "shop:write");
        if (!approval.status.equals("PENDING")) return approval.view();
        if (!approve) {
            approval.status = "REJECTED";
        } else {
            approval.status = "EXECUTING";
            try {
                Result result = shops.updateHoursIfUnchanged(approval.shopId, approval.beforeHours, approval.hours);
                approval.result = result;
                approval.status = Boolean.TRUE.equals(result.getSuccess()) ? "EXECUTED" : "FAILED";
            } catch (RuntimeException ex) {
                // 写入后异常可能意味着结果不确定，绝不自动重试。
                approval.status = "FAILED";
                approval.result = "执行结果不确定，请查询店铺状态；此审批不可重试";
                log.warn("agent approval execution failed id={}", id);
            }
        }
        log.info("agent approval decision id={} user={} status={}", id, userId, approval.status);
        return approval.view();
    }

    private Approval owned(long userId, UUID id) {
        Approval approval = approvals.get(id);
        if (approval == null || approval.userId != userId) throw new AgentException(HttpStatus.NOT_FOUND, "审批不存在");
        if (!clock.instant().isBefore(approval.expiresAt)) {
            approvals.remove(id);
            throw new AgentException(HttpStatus.GONE, "审批已过期");
        }
        return approval;
    }

    private JsonNode parse(String input) {
        if (input == null || input.length() > 4096) throw bad("工具参数过长或为空");
        try {
            JsonNode node = json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(input);
            if (node == null || !node.isObject()) throw bad("工具参数必须是 JSON 对象");
            return node;
        } catch (java.io.IOException ex) { throw bad("工具参数 JSON 无效"); }
    }
    private AgentException bad(String message) { return new AgentException(HttpStatus.BAD_REQUEST, message); }
}
