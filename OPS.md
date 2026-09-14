# RabbitMQ 运维诊断与受控重放

入口：`http://localhost:8080/ops-agent.html`，或商家助手侧栏「队列诊断」。这是同一 Spring Boot 应用里的运维模式，复用官方 Java SDK 及模型传输；没有多 Agent 编排。商家店铺权限不自动升级为运维权限。

## 本地启用

1. 停止旧后端（只停止本项目进程）。运维代码和新的消费者处理在重启后生效。
2. 管理员确认账号的运维职责后，在启动终端设置：

```zsh
export OPS_ENABLED=true
export OPS_ENVIRONMENT=local
# 填经管理员批准的真实用户ID，多个ID用逗号分隔；默认两组均为空
export OPS_DIAGNOSTICIANS='经批准的用户ID'
export OPS_OPERATORS='经批准的用户ID'
mvn spring-boot:run
```

`merchant01` 的用户ID为1010，但不会自动授予它运维权限。操作人必须同时属于两组。L1按钮不需要模型 Key；自然语言入口沿用 `OPENAI_AGENT_ENABLED`、`OPENAI_API_KEY`、`OPENAI_MODEL` 和 `OPENAI_BASE_URL`。

首次启用会执行幂等的 `src/main/resources/db/ops.sql`，只新增五张 `hmdp_ops_*` 表，不修改/清空业务表。生产环境应由迁移账号预先执行，应用账号只授予所需 DML 权限；当前开发版启动迁移需 DDL 权限。保留 MySQL 数据卷，不要运行 `docker compose down -v`。

## L1：证据报告

手动诊断或模型调用 `DiagnoseMq` 时，Service 自动采集：

- E1：固定主队列 `seckillQueue` 的 ready 消息数和消费者数（被动声明，只读，不拉消息）。
- E2：固定死信队列 `seckillQueue.dlq` 的 ready 消息数和消费者数。
- E3：JDBC `isValid(2)` 健康探测，不接受任意SQL。
- E4：最近15分钟、最多30条消费审计事件，包含时间、关联号、结果和固定错误分类；不返回消息体、用户信息、密码、API Key或原始堆栈。

每次报告生成 `incidentId`，绑定发起人、环境、时间，写入 MySQL。报告把事实、根因假设、缺失证据、处置建议分开。快照不能证明持续积压；数据库连接正常不能证明所有SQL正常；没有失败事件不能证明没有故障。

目前未接入外部告警、Prometheus、分布式 Trace、完整日志平台、unacked/历史吞吐率。关联号是本次消费的 correlation ID，不冒充 Trace ID。证据是规则生成的结构化报告，不是模型凭空推断。模型只负责选择诊断/申请工具，不收到诊断报告和原始日志；用户在聊天框主动输入的内容仍会发送给配置的模型服务商。

## L2：审批与重放

1. 根据10分钟内自己的诊断事件，申请1至5条重放；前后端都限制数量。
2. 审批绑定用户、环境、incidentId、固定源/目标队列、数量及10分钟有效期。批准的是执行时队首最多N条，不是某组已预览消息。页面明确展示风险。
3. 人工勾选「故障已修复并接受影响」，再确认；模型没有确认或执行工具。拒绝不操作消息。
4. 执行前及每条消息前再次验证权限、主队列消费者、DLQ无其他消费者、数据库可用。它们只是最低门槛，不代替人工故障判断。
5. MySQL条件更新领取审批，并用数据库资源锁禁止跨进程并行重放。固定每秒最多1条。每条先验证订单身份，记录 `PUBLISHING`，mandatory发布且等待 publisher confirm，再标记 `PUBLISHED` 并ACK源消息。失败则归还未确认源消息。
6. 发布完成状态是 `PUBLISHED`，不是业务成功。点击「验证业务消费结果」查询同 actionId/orderId 的 `CONSUMED` / `DUPLICATE` 审计及当前诊断快照，结果持久化。

ACK、发布和数据库事务不是跨系统原子事务：中断可能造成重复，不承诺 exactly-once。消费者通过现有订单Service中的事务、券行锁和订单身份检查，在同一券上串行化查重、扣库存与保存，防止重放再次扣库存。原消息主键保持不变，不生成新的订单ID。日志关联和权限不从模型文本推断。

消费者最多尝试3次；最终失败时先用 mandatory+publisher confirm 投递DLQ，确认后ACK原消息。DLQ发布失败则原消息重新入队。使用应用层确认投递而非修改已有主队列 x-arguments，避免删队列迁移；新增DLQ在应用启动声明。保留原生产者交换机及路由。

## 中断与恢复

- 审批、事件、逐条投递状态、验证结果都在MySQL，不依赖内存。原商家营业时间审批仍为旧版内存机制，本次持久化只用于运维。
- 重复确认不会重新执行已领取的审批。
- 执行异常标记 `UNCERTAIN`，全局资源锁保留；进程被强制终止可能保留 `EXECUTING`。重启不自动续跑，也不自动释放锁。
- 管理员必须先确认原执行进程停止，核对 actionId 下的逐条记录、DLQ、订单和消费事件，记录处理结论后再通过受控数据库维护解除**该 actionId**持有的锁。当前没有网页强制解锁入口，避免把不确定状态伪装成可以安全重试。
- 重放可能创建订单和扣减库存，不能自动撤销。不要用清队列、删除业务订单或重置Redis库存来掩盖失败。

## 边界与上线前工作

这是开发版闭环，不是生产级自动运维：尚需独立只读观测凭据、租户/服务更细粒度授权、每用户配额、审计保留/归档、独立审批人和完善的故障恢复工具。MySQL不可用时无法持久化诊断，不会假称报告保存成功。数据库连接池获取连接仍受数据源超时控制。

旧业务Controller的权限风险、生产者投递与Redis预约的一致性、其他绕过订单Service的业务写入口不在本次闭环保障内，上线前需另行治理。本次不自动重放现有消息，不自动授予商家全队列权限。

## 验证

```zsh
mvn -Dtest='com.hmdp.ops.*Test,com.hmdp.agent.*Test,com.hmdp.cache.*Test' test
node --test tests/*ui.test.cjs
# 本地MySQL集成：新增运维表，测试审批回滚；随机订单测试清理自身数据
RUN_LOCAL_INTEGRATION=true mvn -Dtest=OpsPersistenceIntegrationTest test
```

真实broker测试必须使用单独创建、以 `hmdp-ops-test-` 开头的 vhost，使用 `RUN_RABBIT_INTEGRATION=true OPS_TEST_VHOST=<隔离vhost>` 运行 `OpsBrokerIntegrationTest`。不可指向默认业务vhost。该测试只用合成消息和模拟订单Service；真实订单事务/并发另由MySQL集成测试覆盖。

设计参考：[RabbitMQ 消费ACK与发布确认](https://www.rabbitmq.com/docs/confirms)、[死信机制](https://www.rabbitmq.com/docs/dlx)。
