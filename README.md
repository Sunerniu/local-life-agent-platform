# MerchantPilot

面向本地生活场景的商家运营与智能辅助平台，支持商铺检索、探店评价、优惠券秒杀，并提供商家助手和受控队列诊断能力。

## 技术栈

Java 17 · Spring Boot 2.7.4 · MyBatis-Plus · MySQL 8 · Redis 7.2 · Caffeine · RabbitMQ · OpenAI Java SDK 4.58.0 · Vue · Docker Compose

## 核心设计

- **秒杀下单**：通过 Redis Lua 原子校验库存与购买资格，结合令牌桶限流和 RabbitMQ 异步下单；消费端使用事务、行锁及订单幂等校验，避免重复扣减库存。
- **热点查询**：采用 Caffeine → Redis → MySQL 二级缓存，通过短 TTL、版本校验和失效通知降低数据库访问压力，防止旧查询结果覆盖新缓存。
- **商家助手**：通过 Function Calling 复用已有 Spring Service，实现店铺、优惠券查询和营业时间修改申请，模型不直接操作数据库或中间件。
- **权限与审批**：服务端校验登录身份、店铺归属、工具白名单及参数；写操作由人工确认，模型不能自行授权或审批。
- **受控运维**：采集队列、消费者、数据库连接与脱敏消费事件，形成证据报告；DLQ 重放须持久化审批，每次最多 5 条、每秒 1 条，并验证业务消费结果。

## 执行流程

```text
商家请求 → 模型选择工具 → 后端鉴权与参数校验 → 业务 Service
                                               └─ 写操作先审批

队列诊断 → 证据与根因假设 → 重放申请 → 人工审批 → 限速执行 → 消费验证
```

## 快速体验

准备 JDK 17、Maven 和 Docker：

```bash
git clone https://github.com/Sunerniu/local-life-agent-platform.git MerchantPilot
cd MerchantPilot
cp .env.example .env    # 已有配置时跳过
docker compose --profile web up -d
mvn spring-boot:run
```

- 首页：`http://localhost:8080/`
- 商家助手：`http://localhost:8080/merchant-agent.html`
- 队列诊断：`http://localhost:8080/ops-agent.html`

模型和运维功能默认关闭，账号与权限需在本地初始化。配置见 [启动说明](SETUP.md)。

## 验证与边界

已编写单元测试、MySQL 并发消费测试及隔离 RabbitMQ 重放测试，覆盖权限校验、重复消费、审批过期和异常中断；未提供生产压测指标。

项目为开发验证版本：缓存采用最终一致性，跨系统消息处理不承诺 exactly-once；商家审批为内存状态，运维审批持久化到 MySQL。尚未接入完整 Trace 和外部告警，旧业务接口权限仍需进一步治理。

## 设计说明

[商家助手](AGENT.md) · [队列诊断](OPS.md) · [二级缓存](docs/shop-cache.md)

基于黑马点评教学项目二次开发，保留原业务包名与启动类；扩展商家助手、受控运维及二级缓存机制。静态资源来源及第三方许可说明见 [SETUP.md](SETUP.md)。
