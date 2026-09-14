# MerchantPilot · 启动说明

## 环境

JDK 17、Maven、Docker Compose。IDEA 的 Project SDK 和 Maven Runner 均选择 JDK 17。

```bash
cp .env.example .env    # 首次使用，已有文件不要覆盖
docker compose --profile web up -d
mvn spring-boot:run
```

MySQL 首次初始化空数据卷时导入示例数据。后端端口为 8081，前端为 8080。默认数据库与 MQ 密码仅供本地开发，禁止直接用于公网部署。

Docker 的 `.env` 不会自动传给本机 Maven / Java 进程；自定义后端参数需在启动终端或 IDEA 运行配置中设置。不要使用 `docker compose down -v`，它会删除数据卷。

## 商家账号

商家助手使用账号密码登录，无需邮箱验证码；新克隆的数据库不包含开发机创建的测试账号。

管理员可在本机终端初始化账号：

```zsh
mvn -DskipTests package
read -rs 'ACCOUNT_SETUP_PASSWORD?设置商家密码（12–128字符）: '
export ACCOUNT_SETUP_PASSWORD
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=local,account-setup \
  --spring.main.web-application-type=none \
  --spring.rabbitmq.listener.simple.auto-startup=false \
  --hmdp.agent.enabled=false \
  --account-setup.create=merchant01
unset ACCOUNT_SETUP_PASSWORD
```

此命令创建新账号，不覆盖已有账号。将输出的用户 ID 配置到本地 `application-local.yaml` 的 `hmdp.agent.grants`；授权示例见 [AGENT.md](AGENT.md)。密码、Key 和本地配置不要提交到 Git。

## 模型配置

在启动后端的同一终端设置：

```zsh
export OPENAI_AGENT_ENABLED=true
export OPENAI_BASE_URL='https://api.openai.com/v1'
export OPENAI_MODEL='实际可用的模型ID'
read -rs 'OPENAI_API_KEY?API Key: '
export OPENAI_API_KEY
mvn spring-boot:run
```

兼容服务需支持 Chat Completions 和严格工具调用参数；地址填写基础路径，不追加 `/chat/completions`。Key 必须属于对应服务商。第三方服务会收到商家对话与工具查询结果，接入前确认可信。

## 运维配置

管理员明确授权后，设置真实用户 ID；操作人须同时属于两组：

```zsh
export OPS_ENABLED=true
export OPS_ENVIRONMENT=local
export OPS_DIAGNOSTICIANS='经批准的用户ID'
export OPS_OPERATORS='经批准的用户ID'
```

重新启动后端生效。首次启用会创建五张运维表，需要相应 DDL 权限；只读诊断按钮无需模型 Key。详见 [OPS.md](OPS.md)。

## 常见问题

- **8081 被占用**：用 `lsof -nP -iTCP:8081 -sTCP:LISTEN` 确认进程，只停止本项目的旧后端。
- **页面无法访问接口**：检查容器状态及后端是否启动；Nginx 将 `/api` 转发到宿主机 8081。
- **VPN 下无法拉取镜像**：检查 Colima 的 DNS 和 Docker daemon 代理；虚拟机的 localhost 不是宿主机，不要直接复制他人的代理地址。
- **登录后无权限**：商家店铺权限与运维权限独立配置，不会随账号创建自动授予。

## 来源

项目基于 [原教学项目](https://github.com/haopengmai/dianping) 继续开发；基础静态页面来自 [cs001020/hmdp 的 init 分支](https://github.com/cs001020/hmdp/tree/init/src/main/resources/nginx-1.18.0/html/hmdp)。第三方组件版权与许可保留在对应目录中。
