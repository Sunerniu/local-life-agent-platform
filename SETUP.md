# 本地环境搭建（macOS + Docker）

## 零、开发工具

后端统一使用 **Java 17 LTS + Spring Boot 2.7.4**，Maven 需 3.6.3 或更高版本。

```bash
brew install openjdk@17 maven
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

IntelliJ IDEA 中将 Project SDK 和 Maven Runner JRE 都设为 `homebrew-17`。本机已安装并注册；IDEA 若已打开，重新载入项目以读取配置。

Merchant Agent 的密钥、权限和审批接口见 [AGENT.md](AGENT.md)。默认不启用 Agent，普通业务仍可启动。

macOS 可使用 Docker Desktop，也可使用轻量的 Docker CLI + Colima：

```bash
brew install docker docker-compose colima
colima start --cpu 2 --memory 4 --disk 30
```

## 一、启动中间件

```bash
cd /Users/ergou/Code/Shop_agent
cp .env.example .env        # 已经有 .env 就跳过，按需改端口密码
docker compose up -d
```

三个容器：

| 服务 | 镜像 | 端口 | 账号 / 密码 |
| --- | --- | --- | --- |
| MySQL | mysql:8.0 | 3306 | root / 123456 |
| Redis | redis:7.2-alpine | 6379 | 无密码，用 6 号库 |
| RabbitMQ | rabbitmq:3.9-management | 5672（AMQP）、15672（后台） | hmdp / 123456 |

看状态：`docker compose ps`，等三个都变成 healthy 再启后端。

MySQL 首次启动会自动执行 `src/main/resources/db/hmdp.sql`，建好 11 张表并灌入初始数据，不需要手动导入。验证：

```bash
docker exec -it hmdp-mysql mysql -uroot -p123456 -e "use hmdp; show tables; select count(*) from tb_shop;"
```

RabbitMQ 管理后台：<http://localhost:15672>（hmdp / 123456）。

> 初始化脚本只在数据卷为空时跑一次。改了 SQL 想重来：`docker compose down -v && docker compose up -d`（会清空数据）。

## 二、配置邮箱验证码（当前跳过）

当前按游客浏览使用，无需填写邮箱。登录页显示游客模式说明；点赞、关注、发帖和抢券仍需要登录，未绕过后端身份校验。后续启用时需恢复登录表单并将手机号提示改为邮箱提示。

登录走的是邮箱验证码，不配就登不进去。QQ 邮箱：设置 → 账户 → 开启 POP3/SMTP 服务 → 生成 16 位授权码（不是 QQ 密码）。

```bash
cp src/main/resources/application-local.yaml.example src/main/resources/application-local.yaml
```

填进去即可，该文件已 gitignore。

## 三、启动后端

IDEA 里直接跑 `HmDianPingApplication`，或：

```bash
mvn spring-boot:run
```

监听 8081。数据库/Redis/MQ 的连接参数在 `application.yaml` 里都写成了 `${环境变量:默认值}`，默认值与上面的容器完全对齐，所以不设任何环境变量也能直接连上。

## 四、前端（可选）

前端已补齐到 `nginx-1.18.0/html/hmdp/`。原仓库不含这些文件，本次使用同一教学项目 [cs001020/hmdp 的 init 分支](https://github.com/cs001020/hmdp/tree/init/src/main/resources/nginx-1.18.0/html/hmdp)中的静态资源，并添加游客登录提示。

```bash
docker compose --profile web up -d
```

以后启动容器版 nginx（配置见 `docker/nginx.conf`，已把 `/api` 反代指向 `host.docker.internal:8081`）：

```bash
docker compose --profile web up -d
```

访问 <http://localhost:8080>。

项目自带的 `nginx-1.18.0/nginx.exe` 是 Windows 版，Mac 上跑不了，用上面的容器方式即可。

## 本次相对原项目的改动

| 文件 | 改动 | 原因 |
| --- | --- | --- |
| `docker-compose.yml` | 新增 | 一键起 MySQL / Redis / RabbitMQ / nginx |
| `docker/nginx.conf` | 新增 | 容器版 nginx，`/api` 反代改为 `host.docker.internal:8081` |
| `.env` / `.env.example` | 新增 | compose 的端口与账号密码 |
| `pom.xml` | connector 5.1.47 → 8.0.33 | 配合 MySQL 8 |
| `application.yaml` | 驱动改 `com.mysql.cj.jdbc.Driver`；URL 加 `allowPublicKeyRetrieval=true`；时区 UTC → Asia/Shanghai；**RabbitMQ 端口 15672 → 5672**；全部参数改为 `${环境变量:默认值}`；新增 `hmdp.upload.dir` 与 `hmdp.mail.*` | 15672 是管理后台端口，AMQP 走 5672，原配置连不上 MQ；MySQL 8 不加 `allowPublicKeyRetrieval` 会报 Public Key Retrieval is not allowed |
| `RedissonConfig.java` | 硬编码 `redis://localhost:6379` → 读 `spring.redis.*` | 换 Redis 地址时容易只改 yaml、漏改这里 |
| `SystemConstants.java` / `UploadController.java` | 移除 Windows 路径常量 `IMAGE_UPLOAD_DIR`，改为注入 `hmdp.upload.dir` | 原路径 `E:\javaweb\...` 在 Mac 上无效，上传必报错 |
| `MailUtils.java` | 邮箱账号与授权码改为读配置；补上 `starttls.enable` | 原来明文写死在源码并提交进了 git |
| `.gitignore` | 忽略 `.env`、`application-local.yaml`、上传的图片 | 防止密钥进仓库 |

## 常见问题

**macOS 开 VPN 时，Colima 拉取镜像报 `[::1]:53 connection refused`**

本机已在 `~/.colima/default/colima.yaml` 配置 Docker daemon 代理：

```yaml
docker:
  proxies:
    http-proxy: http://192.168.5.2:7890
    https-proxy: http://192.168.5.2:7890
    no-proxy: localhost,127.0.0.1,::1,192.168.5.0/24,host.lima.internal,host.docker.internal
```

`192.168.5.2` 是当前 Colima 虚拟机访问 Mac 的网关；`7890` 是当前 VPN 的 HTTP 代理端口。拉取镜像时保持 VPN 代理运行；如果端口变化，同步更新这里并重启 Colima。虚拟机里的 `127.0.0.1` 指向虚拟机自身，不能直接用作 Mac 上的代理地址。配置方式参见 [Docker daemon 代理文档](https://docs.docker.com/engine/daemon/proxy/)。

当前虚拟机的 `/etc/resolv.conf` 是指向 `/run/systemd/resolve/stub-resolv.conf` 的失效软链接，已添加每次启动执行的修复：

```yaml
provision:
  - mode: system
    script: |
      mkdir -p /run/systemd/resolve
      printf 'nameserver 223.5.5.5\nnameserver 119.29.29.29\n' > /run/systemd/resolve/stub-resolv.conf
      systemctl restart dnsmasq
```

以上片段应合并到已有配置，不要覆盖其他设置。修改前的配置备份在 `~/.colima/default/colima.yaml.before-vpn-20260907`。使用 `docker run --rm hello-world` 验证镜像拉取和容器运行。

**建表报 `Invalid default value for 'begin_time'`**
`hmdp.sql` 是 MySQL 5.6 导出的，`tb_seckill_voucher` 用了 `DEFAULT '0000-00-00 00:00:00'`，而 MySQL 8 默认 `sql_mode` 含 `NO_ZERO_DATE`。`docker-compose.yml` 里已通过 `--sql-mode=STRICT_TRANS_TABLES,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION` 放宽。如果你用的是自己装的 MySQL，需要同样调整。

**端口 3306 / 6379 被占用**
本机已装过对应服务。改 `.env` 里的端口，再 `docker compose up -d`；后端侧对应设环境变量 `MYSQL_PORT` / `REDIS_PORT`，或直接改 `application.yaml` 的默认值。

**`Public Key Retrieval is not allowed`**
JDBC URL 少了 `allowPublicKeyRetrieval=true`（MySQL 8 的 caching_sha2_password 认证）。

**发验证码报「邮箱未配置」**
见上面第二步，`hmdp.mail.username` / `hmdp.mail.password` 没填。

**「附近商家」报未知命令 GEOSEARCH**
Redis 低于 6.2。compose 用的是 7.2，若连的是本机旧版 Redis 请升级。
