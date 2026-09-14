# MerchantPilot · 商家助手

## 目标与实现

将自然语言请求转换为受控业务调用，复用已有 Spring Service，而非让模型直接访问 Mapper、MySQL、Redis 或 RabbitMQ。

使用官方 `com.openai:openai-java:4.58.0`，手动注册 `OpenAIClient`。每条消息独立处理，不引入多 Agent 或长期记忆。

| 工具 | 权限 | 行为 |
| --- | --- | --- |
| GetShop | shop:read | 查询授权店铺资料 |
| GetShopVouchers | shop:read | 查询授权店铺优惠券 |
| UpdateShopHours | shop:write | 生成营业时间修改审批 |

## 身份与权限

登录 Token 由后端解析为用户 ID，不采信模型或请求体中的身份声明。工具中的店铺必须与当前所选店铺一致，并通过服务端授权检查。

在本地 `application-local.yaml` 中配置，示例 ID 必须替换为真实授权对象：

```yaml
hmdp:
  agent:
    grants:
      7:
        shop-ids: [1]
        permissions: ["shop:read", "shop:write"]
```

## 审批保护

- 工具白名单及 JSON 校验拒绝未知字段、重复键、非法 ID 和营业时间。
- 修改先生成绑定申请人、店铺和前后参数的审批；模型没有审批工具。
- 确认时重新检查权限，营业时间快照已变化则拒绝覆盖。
- 同一审批不重复执行；执行结果不确定时禁止自动重试。
- 默认最多 4 轮、8 次工具调用，限制请求长度与并发数。

## 接口与边界

接口前缀：`/agent/merchant`，经 Nginx 访问时加 `/api`。

- `GET /context`：授权店铺和审批列表。
- `POST /chat`：提交 `message` 与 `shopId`。
- `POST /approvals/{id}/decision`：明确传入 `approve`。

当前审批保存在单实例内存，默认 10 分钟有效，重启失效。该权限机制只保护新增 Agent 接口，不替代旧业务 Controller 的权限治理。模型可能理解错误，人工仍需核对实际审批内容。

配置与账号初始化见 [SETUP.md](SETUP.md)。
