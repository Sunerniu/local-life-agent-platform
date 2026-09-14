# MerchantPilot · 店铺二级缓存

## 目标与方案

降低热点店铺查询的数据库访问压力。`GET /shop/{id}` 按 Caffeine → Redis → MySQL 查询，本地命中不访问 Redis；缓存 JSON 快照，避免跨请求共享可变对象，并缓存空结果。

## 一致性设计

- 店铺更新在事务提交后增加版本、删除 Redis 缓存并发布失效通知；回滚不触发失效。
- 回源写缓存时通过 Lua 校验版本，防止旧查询结果覆盖新状态。
- 本地分片锁协调加载与失效，同实例并发回源合并。
- Pub/Sub 通知各实例清理本地缓存，短 TTL 处理通知丢失。

## 默认配置

| 环境变量 | 默认值 |
| --- | --- |
| SHOP_CACHE_MAXIMUM_SIZE | 10000 |
| SHOP_CACHE_TTL | 30s |
| SHOP_CACHE_CHANNEL | hmdp:shop:invalidate:<Redis数据库编号> |

Redis 正常结果缓存 30 分钟，空结果缓存 2 分钟。不同环境应隔离失效频道。

## 边界与验证

这是最终一致性方案，不是强一致性。Redis 删除或广播失败时，旧值可能保留到 Redis TTL；不能声称仅靠本地 TTL 收敛。直接 SQL、通用 CRUD 和旧演示缓存入口不自动广播，也未适配 Redis Cluster 多键 hash slot。

```bash
mvn -Dtest=ShopCacheTest test
```

测试覆盖缓存顺序、空结果、失效通知、事务提交/回滚与并发回填，不代替真实多进程集成测试。
