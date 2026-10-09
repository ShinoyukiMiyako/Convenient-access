# AccessHub

面向 Minecraft 1.20.1 Forge **专用服务器**的运维 mod（mod id `shinoyuki_accesshub`）：白名单与玩家离线认证、内置 HTTP 管理 API、多线路接入统计、整合包分发与版本门控、Tab 列表增强。

> 仓库名 `Convenient-access` 是历史遗留。v1 是 Bukkit/Arclight 插件 ConvenientAccess（保留在 tag `v1.0-final`），v2 起整体重写为 Forge mod AccessHub。v1 的源码仍留在 `src/main/java/com/xaoxiao/convenientaccess/` 作参考，**不参与编译，也不再提供任何功能**。

## 功能概览

| 模块 | 说明 |
|------|------|
| 白名单 | 玩家名优先、UUID 首次登录后补；条目可临时禁用而不删除；LOGIN 协商阶段提前拦截，PLAY 阶段兜底 |
| 玩家离线认证 | 游戏内 `/register` `/login`，未认证时冻结在登录点并限制一切操作，超时踢出；密码以 bcrypt 存储 |
| 免密登录（DeviceAuth） | 客户端装同一个 jar 并 `/enroll` 一次后，凭设备 Ed25519 密钥自动完成挑战应答；任何失败静默回退到密码登录 |
| HTTP 管理 API | 内置 Jetty，默认 `0.0.0.0:22222`；白名单 CRUD、管理员账号与 JWT、操作日志、玩家数据、服务器性能、物品图标 |
| QQ 机器人对接 | 管理员个人识别码与 QQ 绑定、外部渠道向游戏内公屏发言 |
| 多线路接入 | 内置 TCP 转发器按入口端口区分线路并统计各线在线人数，解析 PROXY protocol v2 取回玩家真实 IP；内置 WebSocket 延迟探针 |
| 整合包分发 | 版本草稿/发布/回滚、条目管理、自研文件直传阿里云 OSS、对外公开清单（自有 CDN 优先 + OSS 直连兜底）、进服版本门控 |
| Tab 列表增强 | 服务端渲染 header/footer（时间、运行时长、TPS、MSPT、CPU、内存）与彩色延迟后缀，客户端无需装 mod |
| 主动延迟探针 | 以 Ping/Pong 主动测量玩家延迟，取代原版 keep-alive 口径（15 秒采样、约两分钟才收敛） |
| 数据库备份 | 定时备份 SQLite 数据库，可压缩，按保留天数清理 |

## 运行环境

- Minecraft 1.20.1 + Forge 47.x（按 47.4.20 构建，版本范围 `[47,)`）
- Java 17
- 专用服务器。内置服务器（单人存档、对局域网开放）下服务端功能整体不启用，命令也不注册
- 可选：[spark](https://spark.lucko.me/) mod。装了则性能接口返回精确的 TPS / MSPT / CPU，未装自动降级为 JVM 数据

SQLite、Jetty、bcrypt 均已随 jar 打包，无需额外安装。

## 安装与首次启动

1. 把 `shinoyuki_accesshub-<版本>.jar` 放进服务端 `mods/` 目录后启动服务器。mod 不支持热加载，换版本需要重启。
2. 首次启动会在 `config/Shinoyuki-Optimize/shinoyuki_accesshub/` 下生成配置文件 `common.toml` 与数据库 `whitelist.db`。
3. 首次启动还会自动生成三样凭据并写回 `common.toml`：
   - 内置超级管理员 `admin` 的密码（12 位）
   - API 访问令牌（`sk-` 开头，共 64 位）
   - JWT 签名密钥

   前两项会以 WARN 级别在控制台打印一次，之后只能从配置文件读取。
4. HTTP API 默认监听 `0.0.0.0:22222`，公网部署请用防火墙或反向代理收口，TLS 在反代上终结。

> **装上即生效的三个默认值**：`whitelist.enabled`、`whitelist.strict-mode`、`auth.enabled` 默认都是 `true`。给已在运行的服务器首次装上本 mod 后，不在白名单的玩家会被拒绝进服，在白名单的玩家也必须先 `/register`。请先加好白名单，或在首次启动后按需关闭再让玩家进服。

## 配置

配置文件为 TOML：`config/Shinoyuki-Optimize/shinoyuki_accesshub/common.toml`。字段注释随文件生成，下表只列分组与用途。

| 配置段 | 用途 | 默认 |
|--------|------|------|
| `http` | HTTP 服务器开关、端口、监听地址、线程数、闲置超时 | 启用，`0.0.0.0:22222` |
| `api.auth` | API 鉴权开关、管理员密码、API 令牌、JWT 密钥、登录失败锁定 | 启用；5 次失败锁 15 分钟 |
| `api.cors` | 跨域开关与允许的来源 | 启用，`["*"]` |
| `whitelist` | 白名单开关、严格模式、拒绝文案、欢迎语、OP 加入通知 | 启用，严格模式开 |
| `auth` | 玩家离线认证开关、登录超时、密码错误上限、密码强度、绑定码有效期 | 启用；60 秒超时，5 次错误踢出 |
| `auth.device-auth` | 免密登录开关、挑战宽限秒数、服务器实例标识（自动生成，勿改） | 启用；宽限 15 秒 |
| `backup` | 数据库定时备份：计划 `天:时:分`、保留天数、是否压缩 | 每天 02:00，保留 7 天 |
| `network` | 多线路转发器开关、入口监听地址、转发目标端口 | **关闭** |
| `network.probe` | WebSocket 延迟探针开关、监听地址与端口 | **关闭**；`127.0.0.1:25610` |
| `[[network.nodes]]` | 线路定义：id、展示名、入口端口、玩家连接地址、探针地址 | 七条示例线路 |
| `pack.version-gate` | 进服整合包版本门控开关与拒绝文案 | **关闭** |
| `pack.oss` | 自研文件上传的 OSS 端点、Bucket、AccessKey、公开下载基地址 | **关闭** |
| `tablist` | Tab 列表增强开关、延迟颜色阈值、刷新间隔 | 启用 |
| `latency` | 主动延迟探针开关、探测间隔、滑动窗口、下发节流 | 启用 |
| `logging` | 请求日志与调试日志开关 | 关闭 |

### 改配置何时生效

改完 `common.toml` 后执行 `/accesshub reload`（可经 RCON 发送）即可重新读取，多数开关即时生效。以下几类在启动时就已绑定或注入，改动后需要重启服务器：

- `http.*`（HTTP 服务器只在启动时监听一次）
- `api.auth` 下的 `jwt-secret` 与 `login-attempt-limit.*`
- `network` 的入口端口绑定（增删线路、改 `listen-port`）与 `network.probe.*`
- `backup.*`
- `pack.oss` 中用于推导清单兜底地址的 `endpoint` / `bucket` / `public-base-url`

### 升级已有服务器时的配置补全

完整默认配置只在 `common.toml` **不存在**时写入。升级 jar 后的行为分两种：

- `pack.*`、`tablist.*`、`latency.*`：每次启动检查，缺失的键会自动补进文件。
- 其余在后续版本新增的键（典型如整个 `[network]` 段）**不会**自动出现。读取时静默回退到代码内默认值，例如 `network.enabled` 回退为 `false`，转发器不会启动。需要这些功能时须手工把配置段补进 `common.toml`。

## 命令

### 管理命令

`/accesshub`，别名 `/ca`、`/ahub`，要求 OP 等级 2。

| 命令 | 作用 |
|------|------|
| `/accesshub status` | HTTP 服务、白名单开关、严格模式、TPS / MSPT |
| `/accesshub reload` | 重新读取 `common.toml` |
| `/accesshub whitelist add <名字>` | 加白名单（UUID 待该玩家首次登录补全） |
| `/accesshub whitelist remove <名字>` | 移出白名单 |
| `/accesshub whitelist check <名字>` | 查询是否在白名单 |
| `/accesshub whitelist list` | 列出最近加入的 10 条与总数 |
| `/accesshub auth reset <玩家>` | 清除密码记录并吊销免密登记，在线玩家原地降级为未认证 |
| `/accesshub auth unregister <玩家>` | 同 `auth reset` |
| `/accesshub auth info <玩家>` | 注册时间、最后登录时间与 IP、失败次数 |
| `/accesshub auth gencode [玩家]` | 为指定玩家（或全部未注册的白名单玩家）签发一次性绑定码 |
| `/accesshub help` | 帮助 |

### 玩家命令

无权限要求，未认证状态下也可执行。

| 命令 | 作用 |
|------|------|
| `/register <密码> <确认密码>` | 注册，别名 `/reg` |
| `/login <密码>` | 登录，别名 `/l` |
| `/changepassword <旧密码> <新密码>` | 修改密码 |
| `/enroll [绑定码]` | 把当前设备登记为免密设备。已登录时无需绑定码；未登录时须带管理员签发的绑定码 |

注册码校验自 0.2.6 起停用：`/register` 两个参数即可完成注册，多带的第三个参数会被忽略。`auth gencode` 签发的绑定码目前只用于 `/enroll`（换了设备又忘记密码时的登记途径）。

## HTTP API

基础地址 `http://<服务器>:22222/api/v1`，两种凭据：

- `X-API-Key: sk-...`：服务间调用（面板后端、QQ 机器人、问卷后端）。
- `Authorization: Bearer <JWT>`：管理员经 `POST /api/v1/admin/login` 登录后获得，有效期 24 小时。

| 分组 | 路径前缀 | 凭据 |
|------|----------|------|
| 白名单 | `/whitelist` | API 令牌或 JWT |
| 管理员账号 | `/admin` | 登录与注册公开，其余见文档 |
| 识别码与 QQ 绑定 | `/admin/personal-code`、`/bot` | 前者仅 JWT，后者 API 令牌或 JWT |
| 操作日志 | `/logs/operations` | API 令牌或 JWT |
| 玩家与服务器 | `/player`、`/server` | API 令牌或 JWT |
| 物品图标 | `/item-icon` | 公开 |
| 线路状态 | `/net/nodes` | 公开 |
| 整合包（公开） | `GET /pack/latest`、`GET /pack/manifest/{version}` | 公开 |
| 整合包（管理） | `/pack/versions`、`/pack/entries` | **仅 JWT**，API 令牌会被拒绝 |

完整的请求、响应与错误码说明见 [API.md](API.md)。可导入 Postman 的请求集合见 [ConvenientAccess_API_Complete.postman_collection.json](ConvenientAccess_API_Complete.postman_collection.json)。

```bash
# 管理员登录取 JWT
curl -X POST http://localhost:22222/api/v1/admin/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"<common.toml 里的 admin-password>"}'

# 用 API 令牌加白名单
curl -X POST http://localhost:22222/api/v1/whitelist \
  -H "X-API-Key: sk-..." -H "Content-Type: application/json" \
  -d '{"name":"PlayerName","source":"ADMIN"}'
```

## 数据与备份

全部状态集中在 `config/Shinoyuki-Optimize/shinoyuki_accesshub/`，备份这一个目录即可带走所有数据：

| 路径 | 内容 |
|------|------|
| `common.toml` | 配置与凭据（含明文 API 令牌、OSS 密钥，注意文件权限） |
| `whitelist.db` | SQLite 数据库（WAL 模式，运行期伴随 `-wal` / `-shm` 文件） |
| `backup/` | 定时备份 |
| `upload-tmp/` | 整合包上传的临时文件，启动时自动清理残留 |

数据库结构版本当前为 10。启动时自动从旧版本逐级迁移，不支持降级：数据库版本高于程序支持的版本时初始化会失败。因此**回滚到旧版 jar 前必须先恢复对应时期的数据库备份**。

> mod 初始化失败（数据库打不开、迁移出错等）不会阻止服务器启动：服务器照常开服，但白名单、玩家认证与 HTTP API 全部不生效，此时任何人都能进服。换版后请确认日志里出现 `AccessHub 服务端启动完成`，而不是 `AccessHub 启动失败`。

| 表 | 用途 |
|----|------|
| `whitelist` | 白名单条目 |
| `operation_log` | 白名单操作与未授权进服的审计日志 |
| `admin_users`、`admin_sessions`、`auth_logs`、`registration_tokens` | 管理员账号、会话、认证日志、管理员注册令牌 |
| `admin_personal_codes`、`admin_qq_bindings` | 个人识别码（仅存哈希）、QQ 绑定 |
| `player_auth`、`player_registration_codes` | 玩家密码哈希、一次性绑定码（仅存哈希） |
| `device_keys` | 免密设备公钥 |
| `pack_version`、`pack_entry` | 整合包版本与文件条目 |
| `sync_tasks` | v1 JSON 同步的遗留表，当前不使用 |
| `database_version` | 结构版本号 |

## 客户端侧

客户端**可以不装**本 mod：白名单与密码登录全部在服务端完成，未装 mod 的客户端和原版客户端都能正常进服。

客户端装上同一个 jar 后多出两项能力：

- **免密登录**。`/login` 后执行一次 `/enroll`，此后进服自动认证。设备私钥存放在 `<游戏目录>/shinoyuki_accesshub/device-<服务器实例标识>.key`，Windows 下经 DPAPI（当前用户）封存，其它平台明文存储并在日志中警告。换机、重装系统后密钥失效，用密码登录后重新 `/enroll` 即可。
- **上报整合包版本**。启动器以 JVM 参数 `-Dshinoyuki.accesshub.pack-version=<版本>` 传入已应用的整合包版本，服务端开启版本门控后据此判定是否放行。

在单人存档里本 mod 不注册命令、不产生网络流量，只在游戏启动时输出一条加载日志。

## 构建

```bash
./gradlew build
```

Windows 下用 `gradlew.bat build`。产物是 `build/libs/shinoyuki_accesshub-<版本>.jar`，这是唯一可部署的文件：Jetty 与 bcrypt 已 shade 并 relocate，sqlite-jdbc 以 jarJar 嵌套件形式携带。同目录带 `-dev`、`-jarjaronly` 后缀的 jar 是中间产物，不要放进 `mods/`。

只跑单元测试：

```bash
./gradlew test
```

版本号在 `gradle.properties` 的 `mod_version`。

> 仓库根目录的 `build.sh`、`build.bat` 以及 `.github/workflows/build.yml` 是 v1 时期的 Maven 脚本，对当前的 Gradle 工程无效，请勿使用。

## 仓库结构

```
src/main/java/com/shinoyuki/accesshub/
  AccessHubMod.java   mod 入口与装配顺序
  api/                HTTP 路由（ApiRouter）与各 Controller
  auth/               管理员认证、玩家离线认证、识别码与 QQ 绑定
  deviceauth/         免密登录服务端与网络通道
  client/deviceauth/  免密登录客户端（设备密钥、DPAPI）
  whitelist/          白名单业务与查询
  event/              登录拦截、认证冻结、线路认领等事件监听
  net/                线路转发器、PROXY protocol 解析、WebSocket 探针
  pack/               整合包版本、条目、发布规划与版本门控
  modpack/oss/        阿里云 OSS PutObject 客户端
  tablist/            Tab 列表增强
  latency/            主动延迟探针
  command/            游戏内命令
  config/             common.toml 读写
  database/           SQLite 连接与结构迁移
  backup/             数据库备份
  http/               Jetty 服务器
src/main/java/com/xaoxiao/convenientaccess/   v1 Bukkit 遗留代码，不参与编译
src/main/resources/schema/       新库建表脚本
src/main/resources/migrations/   旧库逐级迁移脚本
deploy/network/                  多线路接入的 frp / nginx / 证书配置与部署说明
```

## 文档

- [API.md](API.md)：HTTP API 完整说明
- [deploy/network/README.md](deploy/network/README.md)：多线路接入的部署说明与现网记录

## 许可证

[GNU General Public License v3.0](LICENSE)
