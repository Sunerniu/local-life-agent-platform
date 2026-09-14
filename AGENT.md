# Merchant Agent

在原 Maven 应用内新增 `com.hmdp.agent` 功能模块，不拆分应用。环境固定为 JDK 17、Spring Boot 2.7.4、MySQL 8、Redis 7.2、RabbitMQ。保留原 Controller / Service / Mapper 和 RabbitMQ 生产消费链路。

仅依赖官方 `com.openai:openai-java:4.58.0`，没有 OpenAI Spring Boot Starter、Python、LangChain、LangGraph、向量数据库、多 Agent 或长期记忆。

## 启用

先在当前启动终端设置 `JAVA_HOME` 为 JDK 17。`OPENAI_API_KEY` 仅从环境读取，不写入配置文件或仓库。不要把真实密钥复制到文档、命令历史或聊天记录。

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
# zsh 隐藏输入 API Key
read -rs 'OPENAI_API_KEY?OpenAI API Key: '
export OPENAI_API_KEY
export OPENAI_AGENT_ENABLED=true
# 填入你的 API 项目可用且支持 Function Calling 的模型 ID
export OPENAI_MODEL='你的模型ID'
mvn spring-boot:run
```

IDEA 启动时需在对应 Run Configuration 配置这些环境变量。默认 `OPENAI_AGENT_ENABLED=false`，不创建 Client，也不影响普通业务启动。启用但缺少 Key 或模型 ID 时启动报错。`OpenAIConfiguration` 手动注册并关闭 `OpenAIClient`，超时 30 秒、最多重试 1 次。

### OpenAI 兼容服务

可通过 `OPENAI_BASE_URL` 配置服务商的 API 基础地址，例如 `https://api.example.com/v1`；不要追加 `/chat/completions`。SDK 会调用其下的 `POST /chat/completions`，当前不使用 `/responses`。`OPENAI_MODEL` 填服务商提供的精确模型 ID，`OPENAI_API_KEY` 填该服务商签发的 Key。

本机 local 配置按用户指定默认使用 `https://cf.api.fan/v1` 和 `gpt-5.6-sol`，环境变量可覆盖；公共配置仍默认官方地址。尚未验证该第三方的真实模型调用及严格 Function Calling 兼容性。启用前确认服务商可信：对话和工具查询结果将发送给该服务商。不要向第三方地址发送 OpenAI 官方 Key。

在启动后端的 zsh 终端隐藏输入服务商 Key，然后启用（不在聊天、源码或命令历史里粘贴 Key）：

```zsh
read -rs 'OPENAI_API_KEY?服务商 API Key: '
export OPENAI_API_KEY
export OPENAI_AGENT_ENABLED=true
mvn spring-boot:run
```

先停止旧后端，避免 8081 端口冲突；终端环境变量不自动传给 IDEA 或已有 Java 进程。只设置 Docker 的 `.env` 也不会传给本机启动的后端。

SDK 的 Jackson 2.18.9、Kotlin 1.8.20、OkHttp 4.12.0 依赖版本已与 Boot 依赖管理对齐。本地真实 SDK HTTP 测试不使用外部 API 或真实密钥。

## 身份和权限

沿用 `authorization` 请求头里的现有登录 token，以及 `RefreshTokenInterceptor` / `UserHolder`。不接受请求体中的 userId、角色或模型宣称的权限。不新增游客或开发后门。当前网页为游客模式：启用 Agent 不会自动让游客获得商家身份。

原业务没有商家归属模型，首版用服务端 `hmdp.agent.grants` 显式配置用户 ID 对应的店铺及权限，默认空表、拒绝所有工具。管理员确认真实业务归属后，将下面片段合并到本地配置的现有 `hmdp` 节点中（不要重复创建 YAML 根键）：

```yaml
hmdp:
  agent:
    grants:
      # 示例：必须替换为管理员核实过的用户ID和店铺ID，不能根据用户文本自动授权
      7:
        shop-ids: [1]
        permissions: ["shop:read", "shop:write"]
```

## 工具边界

| 工具 | 权限 | 调用现有 Service | 写入策略 |
| --- | --- | --- | --- |
| GetShop | shop:read + 店铺授权 | IShopService.queryById | 只读 |
| GetShopVouchers | shop:read + 店铺授权 | IVoucherService.queryVoucherOfShop | 只读 |
| UpdateShopHours | shop:write + 店铺授权 | IShopService.update | 先审批，确认后执行 |

工具不注入或访问 Mapper、数据库、Redis、RabbitMQ。营业时间修改继续走原 Service 的事务及缓存清理。对不存在店铺的修改现在返回失败，避免审批误报成功。

模型只提出工具调用。ToolRuntime 对白名单、JSON、额外字段、重复键、ID、营业时间格式和权限做独立校验。模型无法调用审批接口。数据库文本仅作为数据，不能授权或更改运行规则。

## 接口

通过 nginx 使用 `/api/agent/merchant`；直连后端使用 `/agent/merchant`。全部要求登录 token。

1. `POST /agent/merchant/chat`，请求体 `{"message":"把店铺1的营业时间改为10:00-22:00"}`。
2. 响应 `data.status=PENDING_APPROVAL` 时，`data.approvals` 包含审批 ID、固定店铺、固定营业时间和过期时间。此时没有写入。
3. `GET /agent/merchant/approvals/{id}` 查看详情。
4. `POST /agent/merchant/approvals/{id}/decision`，请求体 `{"approve":true}` 确认，或 `{"approve":false}` 拒绝。只能由创建请求的已授权用户确认，执行时重新检查权限；请求不能替换工具、店铺或参数。
5. 检查 `data.status`：`EXECUTED` 成功、`REJECTED` 拒绝、`FAILED` 失败或结果不确定。不要仅根据 HTTP 200 判断业务修改成功。

读工具结果会带对应 tool_call_id 返回给模型，最多 4 轮、8 次工具调用；写工具产生待审批记录后直接停止模型循环。审批结果直接返回前端，不再调用模型。并发模型请求上限 4，消息上限 4000 字符，工具参数上限 4096 字符，工具回传结果上限 24000 字符。

## 审批状态边界

首版是**单实例短期审批**：内存最多 500 条，默认 10 分钟过期，不保存对话。重启后审批 ID 全部失效，需要重新发起。重复或并发确认在同一进程内最多执行一次；异常时记录 FAILED，禁止自动重试，以免数据库已写入但响应丢失导致重复操作。

这不是跨进程持久化审批或分布式 exactly-once 保证。部署多实例前必须实现持久化审批 Service 和业务幂等；不能直接将当前内存版横向扩容。审批日志记录操作 ID、用户、店铺和状态，不记录密钥或完整提示词。该权限边界只保护新增 Agent 接口，不替代原 Controller 的业务权限体系。

## 验证

```bash
mvn -Dtest='com.hmdp.agent.*Test' test
mvn -DskipTests package
```

测试包含 Service 复用、未知工具、参数伪造、跨店铺越权、无登录态、越权审批、权限撤销、超时审批、拒绝、失败不重试、并发/重复确认、调用预算和真实 SDK 本地 HTTP 序列化解析。原 `HmDianPingApplicationTests` 是会写入 Redis 的数据初始化/演示测试，未作为这些隔离测试运行。

真实 OpenAI 联调需要你的 API Key、可用模型 ID、真实登录 token 和已核实的商家授权；这些值没有被虚构或自动填入。

参考：[OpenAI Function Calling 官方文档](https://developers.openai.com/api/docs/guides/function-calling)。
