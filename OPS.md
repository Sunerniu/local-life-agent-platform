# MerchantPilot · 队列诊断与受控重放

## 目标

围绕 RabbitMQ 秒杀队列，形成“诊断 → 证据 → 处置申请 → 审批 → 执行 → 验证”的开发版闭环。模型只能诊断或申请操作，不能自行执行重放。

## L1：只读诊断

自动采集固定范围的证据：

- 主队列与 DLQ 的 ready 消息数量、消费者数量。
- 数据库连接健康状态。
- 近 15 分钟、最多 30 条脱敏消费事件及关联号。

报告区分事实、根因假设、缺失证据和处置建议，以 `incidentId` 绑定用户与环境并持久化。历史报告可在页面查看。

当前未接入完整 Trace、外部告警和历史吞吐指标；消费关联号不是分布式 Trace ID，单次快照也不能证明持续积压。

## L2：人工审批重放

1. 基于本人 10 分钟内的诊断，申请重放 1–5 条消息。
2. 审批固定来源 `seckillQueue.dlq`、目标 `seckillQueue` 和数量上限，有效期 10 分钟。
3. 人工确认故障已修复并接受影响后，后端再次检查权限、队列、消费者与数据库状态。
4. 数据库条件更新领取审批、资源锁防止并行重放；固定每秒最多 1 条。
5. 消息发布获得确认后才 ACK 源消息，再查询消费审计验证业务结果。

批准的是执行时队首最多 N 条，不是预览过的固定消息。重放可能创建订单、扣减库存，不能自动撤销。

## 可靠性与边界

- 消费端通过事务、券行锁和订单身份检查防止重复扣库存；最多尝试 3 次，最终失败后确认投递 DLQ。
- 审批、逐条执行记录、消费事件和验证结果存入 MySQL。
- `PUBLISHED` 仅代表发布完成，不代表业务成功；消费审计中的 `CONSUMED / DUPLICATE` 才用于业务确认。
- 断线可能产生重复投递，不承诺跨系统 exactly-once。
- 异常保留 `UNCERTAIN` 或 `EXECUTING` 与资源锁；重启不自动续跑，需管理员核对后处理。
- 运维授权独立于商家店铺授权；默认关闭，不开放任意队列、SQL 或 Shell 命令。

上线前仍需补充独立观测凭据、细粒度授权、审计归档和受控故障恢复工具。原商家审批仍为内存机制，本页的持久化仅指运维流程。

## 验证

```bash
mvn -Dtest='com.hmdp.ops.*Test' test
node --test tests/ops-agent-ui.test.cjs
# 本地 MySQL：测试审批回滚，随机订单测试清理自身数据
RUN_LOCAL_INTEGRATION=true mvn -Dtest=OpsPersistenceIntegrationTest test
```

真实 RabbitMQ 测试需管理员预先创建独立的 `hmdp-ops-test-` 前缀 vhost，并设置 `RUN_RABBIT_INTEGRATION=true`、`OPS_TEST_VHOST` 后运行 `OpsBrokerIntegrationTest`，禁止指向默认业务 vhost。

配置见 [SETUP.md](SETUP.md)；设计参考 [RabbitMQ 发布确认与消费 ACK](https://www.rabbitmq.com/docs/confirms)。
