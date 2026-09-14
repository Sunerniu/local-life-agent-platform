# dianping
黑马点评
简化版大众点评
项目简介:本项目是一个类似于大众点评等打卡类APP的点评类项目。实现了短信登陆、探店点评、优惠券秒杀、每日签到、好友关注、粉丝关注博主后，主动推送博主的相关博客等多个模块。
用户可以浏览首页的推荐内容，搜索附近的商家，查看商家的详情和评价以及发表探店博客，抢购商家发布的限时秒杀商品。

## 克隆完整项目
git clone https://github.com/haopengmai/dianping.git

## 快速启动（Docker，推荐）
详细步骤见 [SETUP.md](SETUP.md)：

```bash
cp .env.example .env
docker compose up -d          # MySQL 8 / Redis 7.2 / RabbitMQ 3.9，首次启动自动导入 hmdp.sql
```

项目统一使用 Java 17 与 Spring Boot 2.7.4。启动 `HmDianPingApplication` 即可浏览。
新增 Merchant Agent 使用官方 `com.openai:openai-java:4.58.0`，配置和审批流程见 [AGENT.md](AGENT.md)。
新增队列诊断与人工审批DLQ重放页面 `/ops-agent.html`，独立运维授权和使用边界见 [OPS.md](OPS.md)。
邮箱登录目前未启用，Agent 接口仍要求现有登录态及服务端店铺授权。

## 前端环境部署
nginx-1.18.0   启动nginx.exe（Windows）；macOS / Linux 见 SETUP.md 的容器版 nginx
## 后端环境部署
application.yaml 中 Mysql、Redis、RabbitMQ 的配置已改为 `${环境变量:默认值}` 形式，默认值与 docker-compose.yml 对齐，开箱即用；用自己搭的中间件时再改

Redis服务器版本不能低于6.2，获取附近的商家信息GEOSEARCH 命令是在 Redis 6.2 版本中引入

RabbitMQ版本为3.9

| 技术 | 说明 |
| --- | --- |
| Java | 17 LTS |
| Spring Boot | 2.7.4 容器 + MVC 框架 |
| OpenAI | 官方 Java SDK 4.58.0（手动注册 Client Bean） |
| Redis | 分布式缓存 |
| RabbitMQ | 消息中间件 |
| MySQL | 关系型数据库 |
| Lombok | 简化对象封装工具 |
| SMTP | SMTP协议邮箱文件传输 |



## 后端部分功能做了优化
### 优化点1:登录模块，暂时使用个人用户邮箱发送短信验证码->（后续使用阿里云短信服务实现短信登录功能)
用了QQ邮箱的SMTP协议，暂时用个人账号发送短信验证码
### 优化点2:秒杀，高并发的情境下采用消息队列RabbitMQ来优化秒杀下单，减轻数据库的压力。
基于lua保证判断库存是否充足、判断用户是否下单、扣库存、下单并保存用户事件的原子性，同时采用RabbitMQ异步处理高并发情况下的请求。
### 优化点3:秒杀，高并发的情境下使用令牌桶算法进行一定程度上的限流
秒杀是个高并发的过程，短时间内后端访问量巨大，可能会压垮系统，而且只有少许人能秒杀成功，因此首先要做的应该是限流。
### 优化点4:使用Redis中的ZSET数据结构+时间窗口思想，进行用户限流
redis-zset实现用户二级限流，一级限流(5分钟内3次短信仍然登录失败->设计为禁止登录，往redis中写入一个10分钟过期的string并升级为二级限流)、二级限流(5分钟内3次短信仍然登录失败设计为禁止登录，往redis中写入一个30分钟过期的string并重新设置为二级限流)

首先检查一级和二级限制条件，然后分别计算了过去1分钟和过去5分钟内发送验证码的次数。如果过去1分钟内已经发送了一次验证码，或者过去5分钟内发送的验证码次数达到了5次或者是8、11、14...次，那么就会进入相应的限制条件，并向用户发送错误消息。如果没有触发任何限制条件，那么就会生成验证码并发送，然后更新发送时间和次数。
