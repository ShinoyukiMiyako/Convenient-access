# AccessHub API 文档

> 本文档对应 Forge mod `shinoyuki_accesshub`（入口 `AccessHubMod`，包 `com.shinoyuki.accesshub`）。
> 仓库内另有一套 `com.xaoxiao.convenientaccess` 遗留 Bukkit 插件代码，**不再对外提供服务**，本文档不描述它。

## 概述

AccessHub 通过内置 Jetty 暴露一套 RESTful API，用于管理 Minecraft 1.20.1 Forge 服务端的白名单、查询玩家数据与服务器性能、查看各接入线路状态，以及管理与分发整合包。除物品图标端点返回 `image/png` 外，所有端点返回 JSON。

白名单采用"玩家名优先，UUID 后补"策略：加白只需玩家名，UUID 在玩家首次登录时由登录监听器自动补充。

## 快速导航

- [基础信息](#基础信息) - 端口、编码等基础配置
- [认证系统](#认证系统) - 三类端点的鉴权方式与配置位置
- [所有可用端点](#所有可用端点) - 完整路由表（以 `ApiRouter` 为准）
- [响应格式](#响应格式) - 两种响应包装的差异
- [白名单管理 API](#白名单管理-api)
- [管理员认证 API](#管理员认证-api)
- [操作日志 API](#操作日志-api)
- [玩家数据查询 API](#玩家数据查询-api)
- [服务器监控 API](#服务器监控-api)
- [物品图标 API](#物品图标-api)
- [线路接入 API](#线路接入-api)
- [识别码与 QQ 绑定 API](#识别码与-qq-绑定-api)
- [整合包分发 API](#整合包分发-api)
- [UUID 自动补充机制](#uuid-自动补充机制)
- [错误代码说明](#错误代码说明)

## 基础信息

- **基础 URL**: `http://your-server:22222/api/v1`
- **内容类型**: `application/json`
- **字符编码**: `UTF-8`
- **默认端口**: `22222`（`http.port`），监听地址默认 `0.0.0.0`（`http.host`）
- **认证方式**: `X-API-Key`（API 令牌）或 `Authorization: Bearer <jwt>`（管理员 JWT）
- **频率限制**: 服务端**未实现** HTTP 层限流。仅有三处局部限制：`/api/v1/player` 的并发闸门（最多 5 个并发查询）、`/api/v1/admin/login` 的登录失败次数限制、整合包文件上传的并发与排队上限（同时处理 2 个、另排队 2 个）。

## 认证系统

### 配置位置

配置文件为 MC 服务端下的 `config/Shinoyuki-Optimize/shinoyuki_accesshub/common.toml`（TOML，非 YAML）：

```toml
[api.auth]
# 是否启用 API 鉴权 (生产环境强烈建议开启)
enabled = true
# 管理员密码 (首次启动自动生成 12 位)
admin-password = "xxxxxxxxxxxx"
# API 访问令牌 (首次启动自动生成 sk- 开头的 64 位)
api-token = "sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
token-prefix = "sk-"
# JWT 签名密钥 (首次启动生成 base64 256 位强随机). 修改此值会让所有已签发的 token 立即失效
jwt-secret = "..."

[api.auth.login-attempt-limit]
enabled = true
max-attempts = 5
lock-duration-minutes = 15
```

启动时 `api-token`、`admin-password`、`jwt-secret` 若为空会自动生成并写回配置文件。其中管理员密码与 API 令牌会以 WARN 级别打印到控制台（仅生成时那一次），JWT 密钥不打印；之后都只能从配置文件读取。内置超级管理员的用户名固定为 `admin`。

### 三类端点

鉴权逻辑全部集中在 `ApiRouter.isAuthenticated` / `ApiRouter.isPublicEndpoint`：

#### 1. 公开端点（无需任何凭据）

**只有以下六个**，其余端点一律需要凭据：

- `POST /api/v1/admin/login`
- `POST /api/v1/admin/register`
- `GET /api/v1/item-icon`（`<img>` 标签无法携带自定义请求头，故必须公开）
- `GET /api/v1/net/nodes`（面向玩家的线路自查页面匿名访问；只输出各线路人数与连接地址，不含玩家名单与客户端 IP）
- `GET /api/v1/pack/latest`（启动器在玩家登录前读取当前发布版本）
- `GET /api/v1/pack/manifest/{version}`（已发布或已归档版本的清单；`{version}` 不符合版本号格式时不算公开端点，会按需要凭据处理）

后两个只对 GET 方法公开。

#### 2. API 令牌端点（`X-API-Key`）

除公开端点与"仅管理员 JWT"端点外，全部端点都可用 API 令牌访问：

```bash
curl -H "X-API-Key: sk-your-api-token-here" \
     -X GET http://your-server:22222/api/v1/whitelist
```

令牌值取自 `[api.auth] api-token`，服务端以 `MessageDigest.isEqual` 做定长比较（防时序攻击）。

> **注意**：API 令牌**不能**放进 `Authorization: Bearer` 头。该头只走 JWT 校验分支，用 `sk-` 令牌会校验失败并返回 401。

#### 3. 管理员 JWT 端点（`Authorization: Bearer <jwt>`）

JWT 由 `POST /api/v1/admin/login` 签发，有效期 24 小时。

```bash
curl -H "Authorization: Bearer eyJhbGciOi..." \
     -X GET http://your-server:22222/api/v1/admin/me
```

- `GET /api/v1/admin/me` **只认 JWT**：该端点在路由层放行 `X-API-Key`，但控制器会自行从 `Authorization: Bearer` 或 `X-Auth-Token` 头取 JWT，只带 `X-API-Key` 会拿到 401 `未提供认证token`。
- `GET` / `POST /api/v1/admin/personal-code` **只认 JWT**：需要知道"当前是哪位管理员"，只带 `X-API-Key` 返回 401。
- `/api/v1/pack/` 下除上面两个公开 GET 外的全部端点**只认 JWT**：整合包管理会改变全体玩家客户端的发布状态，路由层刻意不让它继承后端与机器人共用的 API 令牌权限。只带 `X-API-Key` 返回 401。
- 携带有效 JWT 调用 `POST /api/v1/whitelist` 时，服务端会用登录管理员的显示名覆盖请求体中的 `added_by_name`，并把 `added_by_uuid` 记为 `WEBUI`（渠道标记，客户端无法伪造）。

> 服务端**不存在** `X-Admin-Password` 请求头的校验逻辑，请勿使用。`[api.auth] admin-password` 仅用于初始化内置超级管理员账号。

### 认证失败响应

```json
{"success":false,"error":"Unauthorized: Invalid API key or token"}
```

HTTP 状态码 401。

### 禁用认证

把 `[api.auth] enabled` 设为 `false` 后，`isAuthenticated` 直接返回 true，所有端点无凭据可访问。仅限完全隔离的内网环境使用。

## 所有可用端点

以下路由表逐条对应 `ApiRouter` 中的 `path.equals(...)` / `path.startsWith(...)` 分支，未列出的路径一律返回 404。

### 白名单管理

| 端点 | 方法 | 描述 | 认证要求 |
|------|------|------|----------|
| `/api/v1/whitelist` | GET | 获取白名单列表（分页、搜索、排序，含被禁用条目） | X-API-Key 或 JWT |
| `/api/v1/whitelist` | POST | 添加白名单条目（仅需玩家名） | X-API-Key 或 JWT |
| `/api/v1/whitelist/batch` | POST | 批量操作（add / remove / enable / disable） | X-API-Key 或 JWT |
| `/api/v1/whitelist/regcode` | POST | 为指定玩家名签发一次性码（仅发码，不加白；`/register` 已停用校验，码仅供 `/enroll`） | X-API-Key 或 JWT |
| `/api/v1/whitelist/stats` | GET | 获取白名单统计信息 | X-API-Key 或 JWT |
| `/api/v1/whitelist/sync` | POST | 兼容桩，JSON 同步已移除 | X-API-Key 或 JWT |
| `/api/v1/whitelist/sync/status` | GET | 兼容桩，返回纯数据库模式标记 | X-API-Key 或 JWT |
| `/api/v1/whitelist/by-name/{name}/status` | PUT | 启用/禁用指定玩家的白名单访问权限 | X-API-Key 或 JWT |
| `/api/v1/whitelist/by-name/{name}/reset-auth` | POST | 重置指定玩家的密码与免密状态 | X-API-Key 或 JWT |
| `/api/v1/whitelist/by-name/{name}` | DELETE | 按玩家名删除白名单条目 | X-API-Key 或 JWT |
| `/api/v1/whitelist/{uuid}` | DELETE | 按 UUID 删除白名单条目 | X-API-Key 或 JWT |

### 管理员认证

| 端点 | 方法 | 描述 | 认证要求 |
|------|------|------|----------|
| `/api/v1/admin/login` | POST | 管理员登录，返回 JWT | 无（公开） |
| `/api/v1/admin/register` | POST | 管理员注册，需注册令牌 | 无（公开，令牌在请求体内校验） |
| `/api/v1/admin/me` | GET | 获取当前管理员信息 | 仅管理员 JWT |
| `/api/v1/admin/generate-token` | POST | 生成管理员注册令牌 | X-API-Key 或 JWT |

### 识别码与 QQ 绑定

| 端点 | 方法 | 描述 | 认证要求 |
|------|------|------|----------|
| `/api/v1/admin/personal-code` | GET | 查询当前管理员的识别码掩码与已绑 QQ | 仅管理员 JWT |
| `/api/v1/admin/personal-code` | POST | 签发/重置个人识别码，明文仅此一次返回 | 仅管理员 JWT |
| `/api/v1/bot/bind` | POST | 凭识别码把 QQ 号认领到某管理员 | X-API-Key 或 JWT |
| `/api/v1/bot/binding` | GET | 查询某 QQ 的绑定（`?qq=`） | X-API-Key 或 JWT |
| `/api/v1/bot/binding` | DELETE | 解除某 QQ 的绑定（`?qq=`） | X-API-Key 或 JWT |

### 操作日志

| 端点 | 方法 | 描述 | 认证要求 |
|------|------|------|----------|
| `/api/v1/logs/operations` | GET | 查询白名单操作日志 | X-API-Key 或 JWT |
| `/api/v1/logs/operations/stats` | GET | 按操作类型统计日志条数 | X-API-Key 或 JWT |

### 玩家与服务器 API

| 端点 | 方法 | 描述 | 认证要求 |
|------|------|------|----------|
| `/api/v1/player` | GET | 获取单个玩家详细数据（`?name=玩家名`） | X-API-Key 或 JWT |
| `/api/v1/server/players` | GET | 获取在线玩家列表 | X-API-Key 或 JWT |
| `/api/v1/server/performance` | GET | 获取服务器性能数据（Spark + JVM） | X-API-Key 或 JWT |
| `/api/v1/server/broadcast` | POST | 外部渠道（QQ）向游戏内公屏发言 | X-API-Key 或 JWT |
| `/api/v1/item-icon` | GET | 按物品 id 返回贴图 PNG（`?id=ns:path`） | 无（公开） |
| `/api/v1/net/nodes` | GET | 各接入线路的实时人数与连接地址 | 无（公开） |

### 整合包分发

| 端点 | 方法 | 描述 | 认证要求 |
|------|------|------|----------|
| `/api/v1/pack/latest` | GET | 当前发布版本的指针 | 无（公开） |
| `/api/v1/pack/manifest/{version}` | GET | 指定版本的文件清单（已发布或已归档） | 无（公开） |
| `/api/v1/pack/versions` | GET | 列出全部版本（含草稿） | 仅管理员 JWT |
| `/api/v1/pack/versions` | POST | 新建草稿，或从已有版本复制为草稿 | 仅管理员 JWT |
| `/api/v1/pack/versions/{id}` | PUT | 修改草稿的版本信息 | 仅管理员 JWT |
| `/api/v1/pack/versions/{id}/entries` | GET | 列出某版本的文件条目 | 仅管理员 JWT |
| `/api/v1/pack/versions/{id}/entries` | POST | 向草稿添加条目 | 仅管理员 JWT |
| `/api/v1/pack/versions/{id}/upload` | POST | 上传自研文件到 OSS 并登记为条目（multipart） | 仅管理员 JWT |
| `/api/v1/pack/versions/{id}/diff` | GET | 与当前发布版本的差异及其修订号 | 仅管理员 JWT |
| `/api/v1/pack/versions/{id}/publish` | POST | 发布草稿 | 仅管理员 JWT |
| `/api/v1/pack/versions/{id}/rollback` | POST | 回滚到某个已归档版本 | 仅管理员 JWT |
| `/api/v1/pack/entries/{id}` | PUT | 修改草稿中的条目 | 仅管理员 JWT |
| `/api/v1/pack/entries/{id}` | DELETE | 删除草稿中的条目 | 仅管理员 JWT |

> **已不存在的端点**：`/api/v1/health`、`/api/v1/server/info`、`/api/v1/server/status`、`/api/v1/players/online`、`/api/v1/players/list`、`/api/v1/worlds/list`、`/api/v1/system/resources`、`/api/v1/register` 在当前实现中均无路由分支，请求会返回 404。在线玩家列表请改用 `/api/v1/server/players`。

## 响应格式

### 控制器响应（`ApiResponse`）

绝大多数端点由控制器经 `ApiResponse` 输出，Gson 默认不序列化 null，因此值为 null 的字段会**整个键缺失**，客户端应按"字段不存在"处理。整合包管理端点是例外，见下方[整合包端点的两处例外](#整合包端点的两处例外)。

成功：

```json
{
  "success": true,
  "data": { },
  "message": "成功获取在线玩家列表",
  "code": 200,
  "timestamp": "2026-08-02T08:59:00.217"
}
```

失败：

```json
{
  "success": false,
  "error": "玩家不存在",
  "code": 404,
  "timestamp": "2026-08-02T08:59:00.217"
}
```

- `timestamp` 是 ISO-8601 本地时间字符串（`yyyy-MM-ddTHH:mm:ss[.SSS]`），**不是**毫秒数。
- `message` 仅在控制器显式传入时才存在。
- **`code` 字段请以 HTTP 状态码为准**。多数控制器在写出前会把 `code` 对齐到 HTTP 状态码（白名单、操作日志、注册令牌、识别码与 QQ 绑定、公屏广播、整合包管理与上传）。但以下三组端点没有做这一步，`code` 仍是 `ApiResponse` 工厂方法的默认值（成功恒 200，`error(...)` 恒 500，只有 400 / 404 与 HTTP 一致）：
  - 管理员认证：`/api/v1/admin/login`、`/api/v1/admin/register`、`/api/v1/admin/me`（HTTP 401 时响应体 `code` 为 500）
  - 玩家数据：`/api/v1/player`（HTTP 429 / 504 时 `code` 为 500）
  - 服务器监控：`/api/v1/server/players`、`/api/v1/server/performance`（HTTP 504 时 `code` 为 500）

### 路由器响应（`ApiRouter` 自身产生的错误）

路径不匹配、方法不支持、handler 未就绪、以及未捕获异常这四类错误由 `ApiRouter` 直接拼串输出，结构不同：

```json
{
  "success": false,
  "error": {
    "code": 404,
    "message": "API endpoint not found"
  },
  "timestamp": 1754103540217
}
```

此处 `error` 是对象，`timestamp` 是毫秒数。物品图标端点的错误响应也是这种结构。

### 整合包端点的两处例外

- **公开端点的成功响应不带包装**。`GET /api/v1/pack/latest` 与 `GET /api/v1/pack/manifest/{version}` 成功时直接返回业务 JSON 对象本身，没有 `success` / `data` / `code` 这一层，键名为 snake_case。失败（404）时仍是上面的 `ApiResponse` 结构。
- **管理端点显式输出 null**。`/api/v1/pack/versions`、`/api/v1/pack/entries` 系列的成功响应中，值为空的字段以 `null` 输出而不是省略键（如草稿的 `publishedAt`、自研条目的 `platform`），这是与前端约定的契约。

## 白名单管理 API

### `GET /api/v1/whitelist`

分页获取白名单列表。该端点供后台管理界面使用，**固定返回全部条目（含 `isActive=false` 的被禁用条目）**。

**查询参数：**

| 参数 | 说明 |
|------|------|
| `page` | 页码，默认 1 |
| `size` | 每页大小，默认 20，上限 999999 |
| `search` | 玩家名模糊匹配（`name LIKE %值%`） |
| `source` | 来源精确匹配，自动转大写 |
| `added_by` | 添加者名模糊匹配（`added_by_name LIKE %值%`） |
| `sort` | 排序字段：`name` / `uuid` / `added_by`(`added_by_name`) / `added_at` / `source` / `created_at` / `updated_at`，非法值回落到 `created_at` |
| `order` | `asc` / `desc`，默认 `desc` |
| `start_date` | 起始时间，作用于 `added_at >= 值` |
| `end_date` | 结束时间，作用于 `added_at <= 值` |

**响应示例：**

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "id": 1,
        "name": "Player1",
        "uuid": "550e8400-e29b-41d4-a716-446655440000",
        "addedByName": "AdminUser",
        "addedByUuid": "WEBUI",
        "addedAt": "2026-08-01T12:00:00",
        "source": "ADMIN",
        "isActive": true,
        "qq": "10001",
        "createdAt": "2026-08-01T12:00:00",
        "updatedAt": "2026-08-01T12:00:00"
      },
      {
        "id": 2,
        "name": "Player2",
        "addedByName": "API",
        "addedByUuid": "API",
        "addedAt": "2026-08-01T13:00:00",
        "source": "SYSTEM",
        "isActive": true,
        "createdAt": "2026-08-01T13:00:00",
        "updatedAt": "2026-08-01T13:00:00"
      }
    ],
    "page": 1,
    "size": 20,
    "total": 100,
    "pages": 5
  },
  "code": 200,
  "timestamp": "2026-08-02T08:59:00.217"
}
```

> **字段命名**：响应键为 Java 字段名的驼峰形式（`addedByName` / `isActive` / `createdAt`），**不是** snake_case。
>
> **UUID 待补充**：如上例中的 `Player2`，`uuid` 为 null 时该键直接缺失。此列表**不包含** `uuid_pending` 字段，请以"是否存在 `uuid` 键"判断。`qq` 为 null 时同理缺失。
>
> **关于 `isActive`**：`false` 表示玩家仍在白名单中、但被管理员关闭了访问权限，进服时会被拒并展示 `whitelist.disabled-message`（默认"您已在白名单中，但管理员手动关闭了您的访问权限"）。可通过 `PUT .../status` 端点切换。

### `POST /api/v1/whitelist`

添加白名单条目。只需玩家名，UUID 在玩家首次登录时自动补充。

**请求体：**

```json
{
  "name": "PlayerName",
  "source": "ADMIN",
  "added_by_name": "AdminName",
  "added_by_uuid": "API",
  "added_at": "2026-08-01T12:00:00",
  "qq": "10001"
}
```

**参数说明：**

- `name`（必需）：玩家名，须为 **3-16 位 `[a-zA-Z0-9_]`**，不满足返回 400 `玩家名格式无效: 需为 3-16 位字母、数字或下划线`（空白或超过 64 位时消息为 `玩家名称格式无效`）。
- `source`（必需）：来源，枚举 `WhitelistEntry.Source` 只接受 **`PLAYER`、`ADMIN`、`SYSTEM`** 三个值。传其它值（含 `API`）返回 400 `来源类型无效`。
- `added_by_name`（可选）：添加者名，缺省 `API`。
- `added_by_uuid`（可选）：添加者标识，兼作渠道标记，缺省 `API`。仅在提供了 `added_by_name` 时才读取此字段。
- `added_at`（可选）：ISO-8601 本地时间，缺省当前时间；格式错误返回 400。
- `qq`（可选）：联系 QQ，空串归一为 null。

> 携带管理员 JWT 调用时，`added_by_name` / `added_by_uuid` 会被服务端强制覆盖为管理员显示名 / `WEBUI`，请求体中的同名字段被忽略。

**最简请求：**

```json
{ "name": "PlayerName", "source": "ADMIN" }
```

**成功响应（HTTP 201）：**

```json
{
  "success": true,
  "data": {
    "name": "PlayerName",
    "added": true,
    "uuid_pending": true,
    "message": "玩家已添加到白名单，UUID将在首次登录时自动补充",
    "registration_code": "ABCD-2345",
    "code_expires_minutes": 1440
  },
  "message": "玩家添加成功",
  "code": 201,
  "timestamp": "2026-08-02T08:59:00.217"
}
```

`registration_code` / `code_expires_minutes` 仅在玩家离线认证启用（`[auth] enabled = true`）时随回执一并签发，否则这两个键缺席。

**错误：** 缺少 `name` 或 `source`、玩家名格式无效、来源类型无效、`added_at` 格式错误均返回 400；玩家已在白名单中或写库失败返回 409 `玩家已在白名单中或添加失败`。

### `DELETE /api/v1/whitelist/{uuid}`

按 UUID 删除。`{uuid}` 必须匹配标准 36 位带连字符格式，否则 400。

**响应示例：**

```json
{
  "success": true,
  "data": {
    "uuid": "550e8400-e29b-41d4-a716-446655440000",
    "name": "PlayerName",
    "removed": true
  },
  "message": "玩家移除成功",
  "code": 200,
  "timestamp": "2026-08-02T08:59:00.217"
}
```

**错误：** UUID 格式非法 400；条目不存在 404。

### `DELETE /api/v1/whitelist/by-name/{name}`

按玩家名删除，用于 UUID 尚未补充的条目。`{name}` 需 URL 编码，服务端会解码。若条目已有 UUID 则按 UUID 删除，否则按名删除。

**响应示例：**

```json
{
  "success": true,
  "data": {
    "name": "PlayerName",
    "uuid": "550e8400-e29b-41d4-a716-446655440000",
    "removed": true
  },
  "message": "玩家移除成功",
  "code": 200,
  "timestamp": "2026-08-02T08:59:00.217"
}
```

条目 UUID 为空时 `uuid` 键缺席。**错误：** 名称非法 400；条目不存在 404。

### `PUT /api/v1/whitelist/by-name/{name}/status`

启用/禁用指定玩家的白名单访问权限（按玩家名定位，因 UUID 待补充的条目 `uuid` 为空）。

禁用（`is_active=false`）后，该玩家**仍保留在白名单中**，但进服会被拒绝并展示 `whitelist.disabled-message`。重新启用即恢复访问。与 `DELETE` 的区别：禁用是可逆的临时关停，不删除条目、不丢失 QQ 与添加者信息。

`{name}` 需做 URL 编码。

**请求体：**

```json
{ "is_active": false }
```

**响应示例：**

```json
{
  "success": true,
  "data": {
    "name": "PlayerName",
    "is_active": false
  },
  "message": "已禁用",
  "code": 200,
  "timestamp": "2026-08-02T08:59:00.217"
}
```

**错误：** 玩家名非法或缺少 `is_active` 返回 400；玩家不存在返回 404。

### `POST /api/v1/whitelist/by-name/{name}/reset-auth`

重置指定玩家的登录凭据：删除其密码记录，并吊销已登记的设备免密绑定（`device_keys` 中的公钥）。玩家此后须重新 `/register` 设置密码，并重新 `/enroll` 才能恢复免密登录。

两者必须一起清：只删密码而保留设备公钥，玩家用原客户端进服仍会被免密验签自动解冻，重置形同虚设。

该玩家若正在线上，会被**原地降级为未认证**——冻结在当前位置、收到重置提示，并重新起算 `auth.timeout-seconds` 登录超时窗口，而非直接踢下线。

**白名单条目本身不受影响**：玩家仍在白名单中，`is_active` 不变。这与 `DELETE`（移出白名单）和 `PUT .../status`（关停访问权限）是三件不同的事。

用 POST 而非 DELETE，是因为 DELETE 路由按前缀截取玩家名，会把 `{name}/reset-auth` 整段当成玩家名。

`{name}` 需做 URL 编码。无请求体。

**响应示例：**

```json
{
  "success": true,
  "data": {
    "name": "PlayerName",
    "password_cleared": true,
    "device_revoked": false
  },
  "message": "已重置该玩家的密码与免密状态",
  "code": 200,
  "timestamp": "2026-08-19T10:12:33.421"
}
```

`password_cleared` 与 `device_revoked` 分别表示本次实际清掉了什么；玩家没登记过免密时 `device_revoked` 为 `false`，属正常情况。

**错误：** 玩家名非法 400；该玩家没有任何认证记录（两项皆无可清）404；玩家认证系统未就绪 503。

### `POST /api/v1/whitelist/batch`

批量操作，`operation` 支持 `add`、`remove`、`enable`、`disable`（大小写不敏感）。单次最多 100 个玩家，`players` 不可为空。

**批量添加：**

```json
{
  "operation": "add",
  "source": "ADMIN",
  "added_by_name": "AdminName",
  "added_by_uuid": "00000000-0000-0000-0000-000000000000",
  "added_at": "2026-08-01T12:00:00",
  "players": [
    { "name": "Player1" },
    { "name": "Player2", "uuid": "550e8400-e29b-41d4-a716-446655440000" }
  ]
}
```

> **批量添加与单条添加的关键差异**：批量添加会立即为每个玩家写入 UUID —— 未提供 `uuid` 时由 `UuidUtils.getOrGenerateUuid` 按玩家名 MD5 生成确定性 UUID（version 3 形态），**不走登录补充流程**。若服务器为正版验证模式，该生成值与玩家真实 UUID 不一致。需要 UUID 自动补充语义时请逐个调用 `POST /api/v1/whitelist`。

**批量删除**（按 `uuid` 定位，每个元素必须有合法 `uuid`）：

```json
{
  "operation": "remove",
  "added_by_name": "AdminName",
  "players": [
    { "uuid": "550e8400-e29b-41d4-a716-446655440000" },
    { "uuid": "550e8400-e29b-41d4-a716-446655440001" }
  ]
}
```

**批量启用/禁用**（按 `name` 定位，无需 `source` / `added_by_*`）：

```json
{
  "operation": "disable",
  "players": [
    { "name": "Player1" },
    { "name": "Player2" }
  ]
}
```

**参数说明：** `operation` 与 `players` 必需；`source` 仅 `add` 必需，取值同样限于 `PLAYER` / `ADMIN` / `SYSTEM`；`added_by_name` 缺省 `API`，`added_by_uuid` 缺省 `00000000-0000-0000-0000-000000000000`（仅 `add` / `remove` 读取）。

**响应示例：**

```json
{
  "success": true,
  "data": {
    "operation": "add",
    "total_requested": 2,
    "success_count": 2,
    "failure_count": 0,
    "success_rate": 1.0,
    "registration_codes_pending": true,
    "registration_codes_hint": "批量加白未逐个签发注册码, 请在游戏内执行 /accesshub auth gencode 为未注册白名单玩家批量补发"
  },
  "message": "批量添加完成",
  "code": 200,
  "timestamp": "2026-08-02T08:59:00.217"
}
```

- 全部成功 HTTP 200，部分成功 HTTP **207**，全部失败 HTTP 400。
- `success_rate` 是 **0..1 的比例**（`success_count / total_requested`），不是百分比。
- 存在失败项时追加 `errors` 数组（元素为服务端产生的错误描述）。
- `registration_codes_pending` / `registration_codes_hint` 仅在玩家离线认证启用且至少成功一条时出现：批量加白**不会**逐个签发注册码。

### `POST /api/v1/whitelist/regcode`

> 注册码校验自 0.2.6 起临时停用：玩家 `/register <密码> <确认密码>` 两参数即可注册，不再需要码。
> 本端点与下面的发码字段仍然可用，但签发出来的码当前只对 `/enroll`（换机免密登记）有效。

为指定玩家名签发一次性注册码，仅发码、不改动白名单。与 `POST /api/v1/whitelist`（加白即发码）解耦：当玩家已在白名单（如问卷审核时已加白）时再调加白会撞 409 拿不到码，此端点直接重签注册码，与白名单状态无关。内部会作废该玩家名名下旧的未用码，保证同名同时只有一个有效码；仅返回明文码一次，库内只存其 SHA-256 哈希，明文码不写入操作日志。

主要供问卷后端在玩家凭 hash 自助领码时以服务端身份调用。需玩家离线认证（`common.toml` 的 `[auth] enabled`）启用，否则返回 409。

**请求体：**

```json
{ "name": "PlayerName" }
```

**响应示例：**

```json
{
  "success": true,
  "data": {
    "name": "PlayerName",
    "registration_code": "ABCD-2345",
    "code_expires_minutes": 1440
  },
  "message": "注册码已生成",
  "code": 200,
  "timestamp": "2026-08-02T08:59:00.217"
}
```

**说明：**

- 注册码绑定该玩家名、一次性、`code_expires_minutes` 分钟后过期（`[auth] code-expiry-minutes`，默认 1440）。原用途是游戏内 `/register <密码> <确认> <注册码>`；0.2.6 起 `/register` 不再校验码，该码仅供 `/enroll <码>` 换机登记。
- 缺少 `name` 或名称非法返回 400；玩家认证未启用返回 409 `玩家认证未启用, 无法签发注册码`；生成失败返回 500。

### `GET /api/v1/whitelist/stats`

**响应示例：**

```json
{
  "success": true,
  "data": {
    "totalPlayers": 150,
    "activePlayers": 148,
    "sourceCounts": {
      "ADMIN": 120,
      "SYSTEM": 25,
      "PLAYER": 3
    },
    "recentAdditions": 5,
    "growthTrend": "stable"
  },
  "code": 200,
  "timestamp": "2026-08-02T08:59:00.217"
}
```

**字段说明：**

| 字段 | 说明 |
|------|------|
| `totalPlayers` | 白名单总条目数（含被禁用） |
| `activePlayers` | `is_active = 1` 的条目数 |
| `sourceCounts` | 按 `source` 分组计数，**只统计 `is_active = 1` 的条目** |
| `recentAdditions` | 最近 24 小时内 `created_at` 落入的新增数 |
| `growthTrend` | 恒为 `"stable"`，趋势计算未实现，请勿依赖 |

> 该端点**不返回** UUID 待补充数、删除数、同步状态或缓存状态，这些字段在当前实现中不存在。

### `POST /api/v1/whitelist/sync`

JSON 同步功能已移除，此端点保留为兼容桩，不执行任何同步动作，恒返回 HTTP 200：

```json
{
  "success": true,
  "data": {
    "message": "JSON同步功能已移除,系统现在使用纯数据库模式",
    "mode": "database-only"
  },
  "code": 200,
  "timestamp": "2026-08-02T08:59:00.217"
}
```

### `GET /api/v1/whitelist/sync/status`

同为兼容桩：

```json
{
  "success": true,
  "data": {
    "mode": "database-only",
    "json_sync": "disabled",
    "message": "系统运行在纯数据库模式"
  },
  "code": 200,
  "timestamp": "2026-08-02T08:59:00.217"
}
```

## 管理员认证 API

### `POST /api/v1/admin/login`

公开端点。用户名 + 密码换取 JWT，有效期 24 小时。

**请求体：**

```json
{ "username": "admin", "password": "your-password" }
```

**响应示例：**

```json
{
  "success": true,
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9....",
    "user": {
      "id": 1,
      "username": "admin",
      "displayName": "管理员",
      "isSuperAdmin": true,
      "isAdmin": true
    }
  },
  "message": "登录成功",
  "code": 200,
  "timestamp": "2026-08-02T08:59:00.217"
}
```

**错误：** 缺少 `username` / `password` 或为空返回 400；凭据错误或账号被锁返回 401（消息来自认证服务）。连续失败达 `[api.auth.login-attempt-limit] max-attempts`（默认 5）后锁定 `lock-duration-minutes`（默认 15）分钟。

### `POST /api/v1/admin/register`

公开端点，但请求体内必须携带有效的注册令牌（由 `POST /api/v1/admin/generate-token` 生成）。

**请求体：**

```json
{
  "username": "newadmin",
  "password": "your-password",
  "token": "reg_xxxxxxxxxxxxxxxxxxxxxxxxxxxx",
  "displayName": "新管理员"
}
```

**参数说明：**

- `username`（必需）：`^[a-zA-Z0-9_]{3,20}$`
- `password`（必需）：长度 6-50
- `token`（必需）：注册令牌，`reg_` 前缀，一次性
- `displayName`（可选）：缺省等于 `username`

**响应示例：**

```json
{
  "success": true,
  "data": {
    "username": "newadmin",
    "message": "注册成功"
  },
  "message": "注册成功",
  "code": 200,
  "timestamp": "2026-08-02T08:59:00.217"
}
```

**错误：** 参数缺失/格式不合规/令牌无效/用户名已存在均返回 400。

### `GET /api/v1/admin/me`

**仅接受管理员 JWT**，从 `Authorization: Bearer <jwt>` 或 `X-Auth-Token: <jwt>` 头读取。

**响应示例：**

```json
{
  "success": true,
  "data": {
    "id": 1,
    "username": "admin",
    "displayName": "管理员",
    "email": "admin@example.com",
    "isSuperAdmin": true,
    "isAdmin": true
  },
  "message": "获取成功",
  "code": 200,
  "timestamp": "2026-08-02T08:59:00.217"
}
```

`email` 为 null 时该键缺席。**错误：** 未提供 token 返回 401 `未提供认证token`；token 无效或过期返回 401 `认证失败或token已过期`。

### `POST /api/v1/admin/generate-token`

生成管理员注册令牌。路由层鉴权，`X-API-Key` 与管理员 JWT 均可通过。

**请求体（可选，可为空体）：**

```json
{ "expiryHours": 24 }
```

`expiryHours` 缺省 24，取值须在 1-168 之间，越界返回 400。请求体解析失败时回落到默认值。

**响应示例：**

```json
{
  "success": true,
  "data": {
    "token": "reg_xxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "expiryHours": 24,
    "message": "令牌生成成功"
  },
  "message": "令牌生成成功",
  "code": 200,
  "timestamp": "2026-08-02T08:59:00.217"
}
```

令牌形如 `reg_` + 28 位 URL-safe Base64 字符，总长 32。该令牌用于 `POST /api/v1/admin/register`。

## 操作日志 API

### `GET /api/v1/logs/operations`

**查询参数：**

| 参数 | 说明 |
|------|------|
| `type` | 操作类型精确匹配 |
| `target_uuid` | 目标玩家 UUID |
| `target_name` | 目标玩家名 |
| `operator_ip` | 操作者 IP |
| `start_time` | 起始时间，ISO-8601 `yyyy-MM-ddTHH:mm:ss`，格式错误返回 400 |
| `end_time` | 结束时间，同上 |
| `limit` | 返回条数，默认 100，上限 1000，非法值回落 100 |
| `offset` | 偏移量，默认 0，负数归零 |

**响应示例：**

```json
{
  "success": true,
  "data": {
    "logs": [
      {
        "id": 1,
        "operation_type": "ADD",
        "target_uuid": "550e8400-e29b-41d4-a716-446655440000",
        "target_name": "PlayerName",
        "operator_ip": "127.0.0.1",
        "operator_agent": "curl/8.4.0",
        "request_data": "{\"name\":\"PlayerName\",\"source\":\"ADMIN\"}",
        "response_status": 201,
        "execution_time": 12,
        "created_at": "2026-08-02T08:59:00"
      }
    ],
    "total": 1,
    "limit": 100,
    "offset": 0,
    "has_more": false
  },
  "message": "查询成功",
  "code": 200,
  "timestamp": "2026-08-02T08:59:00.217"
}
```

当前实际写入日志的操作类型有 6 种：

| `operation_type` | 来源 |
|------------------|------|
| `ADD` | `POST /api/v1/whitelist` |
| `REMOVE` | 两个 `DELETE /api/v1/whitelist/...` |
| `SET_ACTIVE` | `PUT .../status` |
| `GENCODE` | `POST /api/v1/whitelist/regcode` |
| `RESET_AUTH` | `POST .../reset-auth` |
| `UNAUTHORIZED_ACCESS` | 不来自 HTTP：登录监听器因白名单拒绝玩家进服时写入（不在白名单、条目被禁用、严格模式下查询失败）。`response_status` 恒为 403，`operator_agent` 为 `Minecraft Client`，`operator_ip` 是该玩家的来源 IP |

> `execution_time` 单位为毫秒。HTTP 来源的日志中，`operator_ip` 优先取 `X-Forwarded-For` 首段，其次 `X-Real-IP`，最后连接远端地址。
>
> `SET_ACTIVE` 与 `GENCODE` 在数据库结构 v7 之前被表的 CHECK 约束拒收，因此较早的库里查不到这两类的历史记录。批量操作（`/whitelist/batch`）不写操作日志。

### `GET /api/v1/logs/operations/stats`

**查询参数：** `start_time`、`end_time`（同上，格式错误返回 400）。

**响应示例：**

```json
{
  "success": true,
  "data": {
    "add": 120,
    "unauthorized_access": 42,
    "remove": 8,
    "gencode": 5,
    "set_active": 3,
    "reset_auth": 1,
    "total": 179
  },
  "message": "统计成功",
  "code": 200,
  "timestamp": "2026-08-02T08:59:00.217"
}
```

> 分项直接从库里按 `operation_type` 聚合，键名为类型的小写形式，按条数降序排列，`total` 等于各分项之和。**时间范围内一条都没有的类型不会出现在响应里**，客户端应把缺失的键当作 0，不要假设固定的键集合。

## 玩家数据查询 API

### `GET /api/v1/player`

获取单个玩家的详细数据。在线玩家读取实时状态，离线玩家直接解析 `playerdata/<uuid>.dat` 的压缩 NBT。

**查询参数：**

- `name`（必需）：玩家名，为空返回 400
- `includeOffline`（可选）：`true` 时允许查询离线玩家，默认 `false`

**请求示例：**

```bash
curl -H "X-API-Key: sk-your-api-token-here" \
     -X GET "http://your-server:22222/api/v1/player?name=PlayerName"

curl -H "X-API-Key: sk-your-api-token-here" \
     -X GET "http://your-server:22222/api/v1/player?name=PlayerName&includeOffline=true"
```

**响应示例（在线玩家）：**

```json
{
  "success": true,
  "data": {
    "playerName": "PlayerName",
    "uuid": "550e8400-e29b-41d4-a716-446655440000",
    "online": true,
    "gameMode": "survival",
    "ping": 62,
    "location": {
      "dimension": "minecraft:overworld",
      "x": -4626.93,
      "y": 71.59,
      "z": 20.71,
      "yaw": 90.0,
      "pitch": 0.0
    },
    "vitals": {
      "health": 20.0,
      "maxHealth": 20.0,
      "armor": 15,
      "foodLevel": 20,
      "saturation": 5.0,
      "exhaustion": 0.0,
      "level": 30,
      "exp": 0.5,
      "totalExperience": 825,
      "remainingAir": 300,
      "maximumAir": 300,
      "fireTicks": 0
    },
    "state": {
      "flying": false,
      "allowFlight": false,
      "invulnerable": false,
      "walkSpeed": 0.1,
      "flySpeed": 0.05,
      "sneaking": false,
      "sprinting": false,
      "swimming": false,
      "gliding": false
    },
    "potionEffects": [
      {
        "type": "minecraft:speed",
        "amplifier": 1,
        "duration": 600,
        "ambient": false,
        "visible": true,
        "showIcon": true
      }
    ],
    "inventory": {
      "main": [
        {
          "type": "minecraft:diamond_sword",
          "amount": 1,
          "slot": "0",
          "damage": 0,
          "maxDurability": 1561,
          "displayName": "传奇之剑",
          "enchantments": {
            "minecraft:sharpness": 5,
            "minecraft:unbreaking": 3
          }
        }
      ],
      "armor": [
        {
          "type": "minecraft:diamond_helmet",
          "amount": 1,
          "slot": "head",
          "damage": 10,
          "maxDurability": 363,
          "enchantments": { "minecraft:protection": 4 }
        }
      ],
      "offHand": {
        "type": "minecraft:torch",
        "amount": 64,
        "slot": "offhand"
      }
    },
    "enderChest": [
      { "type": "minecraft:diamond", "amount": 64, "slot": "0" }
    ]
  },
  "message": "成功获取玩家数据(在线)",
  "code": 200,
  "timestamp": "2026-08-02T08:59:00.217"
}
```

**响应示例（离线玩家，`includeOffline=true`）：**

```json
{
  "success": true,
  "data": {
    "playerName": "PlayerName",
    "uuid": "550e8400-e29b-41d4-a716-446655440000",
    "online": false,
    "source": "offline-nbt",
    "lastSaved": "2026-08-01T20:13:44.512Z",
    "gameMode": "survival",
    "location": {
      "dimension": "minecraft:overworld",
      "x": -4626.93,
      "y": 71.59,
      "z": 20.71,
      "yaw": 90.0,
      "pitch": 0.0
    },
    "vitals": {
      "health": 20.0,
      "foodLevel": 20,
      "saturation": 5.0,
      "exhaustion": 0.0,
      "level": 30,
      "exp": 0.5,
      "totalExperience": 825,
      "remainingAir": 300,
      "fireTicks": 0
    },
    "state": {
      "flying": false,
      "allowFlight": false,
      "invulnerable": false,
      "walkSpeed": 0.1,
      "flySpeed": 0.05
    },
    "potionEffects": [],
    "inventory": { "main": [], "armor": [] },
    "enderChest": []
  },
  "message": "成功获取玩家数据(离线)",
  "code": 200,
  "timestamp": "2026-08-02T08:59:00.217"
}
```

**在线与离线的结构差异：**

| 差异点 | 说明 |
|--------|------|
| `source` / `lastSaved` | 仅离线有；`lastSaved` 为 `.dat` 文件修改时间（ISO-8601 UTC 瞬时） |
| `ping` | 仅在线有 |
| `vitals.maxHealth` / `vitals.armor` / `vitals.maximumAir` | 仅在线有。离线 NBT 无法可靠还原属性修饰符，故刻意省略而不给假值 |
| `state.sneaking` / `sprinting` / `swimming` / `gliding` | 仅在线有，这些是运行期瞬时状态，不落盘 |

**字段说明：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `playerName` | string | 玩家名 |
| `uuid` | string | 玩家 UUID |
| `online` | boolean | 是否在线（**字段名为 `online`，不是 `isOnline`**） |
| `gameMode` | string | 游戏模式，小写：`survival` / `creative` / `adventure` / `spectator` |
| `ping` | int | 延迟毫秒（仅在线）。`latency.probe-enabled` 开启时（默认）是主动 Ping/Pong 探针的估计值，关闭后回落到原版 keep-alive 口径（约两分钟才收敛）。`/api/v1/server/players` 的 `ping` 同此口径 |
| `location.dimension` | string | 维度注册名，如 `minecraft:overworld`（**不是** `NORMAL`/`NETHER`） |
| `location.x/y/z` | double | 坐标 |
| `location.yaw/pitch` | float | 视角 |
| `vitals.*` | - | 生命/饱食/经验/空气/燃烧等数值 |
| `state.*` | - | 飞行能力与运动状态 |
| `potionEffects[].type` | string | 效果注册名，如 `minecraft:speed` |
| `inventory.main` | array | 主背包 36 格（含快捷栏），`slot` 为 `"0"`-`"35"` |
| `inventory.armor` | array | 盔甲栏，`slot` 取 `feet` / `legs` / `chest` / `head` |
| `inventory.offHand` | object | 副手物品，`slot` 为 `offhand`；副手为空时该键缺席 |
| `enderChest` | array | 末影箱物品 |
| 物品 `type` | string | 物品注册名，如 `minecraft:diamond_sword` |
| 物品 `damage` / `maxDurability` | int | 仅可损耗物品才有 |
| 物品 `displayName` | string | 仅设置了自定义名称才有 |
| 物品 `enchantments` | object | 附魔注册名 → 等级，无附魔时该键缺席 |

> 空槽位不会出现在数组中；`inventory` 的 `main` / `armor` 是压缩后的稀疏列表，需靠 `slot` 定位。

**错误响应：**

```json
{
  "success": false,
  "error": "玩家不在线 (提示: 加 ?includeOffline=true 查询离线玩家基本信息)",
  "code": 404,
  "timestamp": "2026-08-02T08:59:00.217"
}
```

| HTTP | 场景 |
|------|------|
| 400 | 缺少 `name` 参数 |
| 404 | 玩家不在线且未加 `includeOffline`；或玩家不存在 / 从未登录过服务器 |
| 429 | `查询请求过多, 请稍后再试`（并发闸门满） |
| 500 | 采集异常，或 `.dat` 文件损坏 |
| 504 | `服务器繁忙, 请稍后重试`（主线程任务超时） |

**注意事项：**

- 数据采集必须在服务器主线程执行，在线查询超时 3 秒、`includeOffline=true` 时 5 秒，超时返回 504。
- 并发上限 5（`Semaphore`），获取许可等待 100 毫秒，抢不到返回 429。
- 玩家名区分大小写（在线查找走 `getPlayerByName`，离线走 `GameProfileCache`）。
- v1 Bukkit 版的 `statistics`（游戏时长/死亡数/击杀数/伤害）**未移植**，当前实现不返回该字段。

## 服务器监控 API

### `GET /api/v1/server/players`

获取在线玩家列表（主线程采集，3 秒超时）。

**响应示例：**

```json
{
  "success": true,
  "data": {
    "count": 1,
    "maxPlayers": 114514,
    "players": [
      {
        "name": "xinglongge",
        "uuid": "ccbbc496-0000-0000-0000-000000000000",
        "dimension": "minecraft:overworld",
        "x": -4626.93,
        "y": 71.59,
        "z": 20.71,
        "health": 20,
        "ping": 62,
        "gameMode": "creative"
      }
    ]
  },
  "message": "成功获取在线玩家列表",
  "code": 200,
  "timestamp": "2026-08-02T08:59:00.217"
}
```

**错误：** 主线程繁忙超时返回 504 `获取在线玩家超时`；采集异常返回 500。

### `GET /api/v1/server/performance`

获取服务器性能数据。TPS / MSPT / CPU 来自 spark mod，内存 / GC / 线程始终来自 JVM MXBean。异步采集，5 秒超时。

**响应示例（spark 已加载，取自 Forge 1.20.1-47.4.0 生产实例）：**

```json
{
  "success": true,
  "data": {
    "source": "spark",
    "tps": {
      "available": true,
      "values": { "last_10s": 19.9998, "last_1m": 19.99996, "last_5m": 19.999998 },
      "server_load_percent": 0.000167
    },
    "mspt": {
      "available": true,
      "values": {
        "last_1m": { "mean": 0.8959, "max": 1.7136, "min": 0.6275, "percentile_95": 1.2496 },
        "last_5m": { "mean": 0.9233, "max": 52.691, "min": 0.6226, "percentile_95": 1.3215 }
      }
    },
    "cpu": {
      "available": true,
      "system":  { "last_10s": 0.00157, "last_1m": 0.00202, "last_15m": 0.00328 },
      "process": { "last_10s": 0.00613, "last_1m": 0.0063, "last_15m": 0.00768 }
    },
    "memory": {
      "heap": {
        "init": 25769803776,
        "used": 6215958528,
        "committed": 25769803776,
        "max": 25769803776,
        "usage_percent": 24.12
      },
      "non_heap": {
        "init": 12582912,
        "used": 405129080,
        "committed": 451280896,
        "max": 2147483648
      },
      "pools": {
        "zgc_old_generation": {
          "used": 3183476736,
          "committed": 3183476736,
          "max": 25769803776,
          "type": "HEAP"
        }
      },
      "source": "jvm"
    },
    "gc": {
      "collectors": {
        "zgc_cycles": { "collection_count": 42, "collection_time": 1180 }
      },
      "total_collections": 42,
      "total_time_ms": 1180,
      "average_time_per_collection": 28.09,
      "source": "jvm"
    },
    "threads": {
      "current_thread_count": 128,
      "daemon_thread_count": 96,
      "peak_thread_count": 141,
      "total_started_thread_count": 3120,
      "deadlocked_threads": 0,
      "source": "jvm"
    },
    "sparkAvailable": true
  },
  "message": "成功获取服务器性能数据",
  "code": 200,
  "timestamp": "2026-08-02T08:59:00.217"
}
```

**字段说明：**

| 字段 | 说明 |
|------|------|
| `source` | `"spark"` 或 `"fallback"`，标明数据来源 |
| `sparkAvailable` | 由 handler 附加，等价于"spark mod 已加载且 API 可用" |
| `tps.values` | 窗口固定为 **`last_10s` / `last_1m` / `last_5m`**（spark `StatisticWindow.TicksPerSecond` 的 `SECONDS_10` / `MINUTES_1` / `MINUTES_5`），**没有 `last_15m`** |
| `tps.server_load_percent` | 按 `(20 - last_1m) / 20 * 100` 计算的负载百分比 |
| `mspt.values` | 窗口为 `last_1m` / `last_5m`，每个窗口是含 `mean` / `max` / `min` / `percentile_95` 的对象，单位毫秒 |
| `cpu.system` / `cpu.process` | 窗口为 `last_10s` / `last_1m` / `last_15m`。**值是 0..1 的比例，不是百分比**（`0.00613` 即 0.613%） |
| `memory.heap` / `memory.non_heap` | 字节数；`usage_percent` 仅堆有，是百分比 |
| `memory.pools` | 键为内存池名（空格转下划线并转小写，如 `zgc_old_generation`），`type` 为 `HEAP` / `NON_HEAP` |
| `gc.collectors` | 键为 GC 名（同样小写化），`collection_time` 与 `total_time_ms` 单位毫秒 |
| `threads` | JVM 线程计数与死锁线程数 |

**spark 不可用时的降级结构：** `source` 变为 `"fallback"`，`sparkAvailable` 为 `false`，且：

- `tps`：三个窗口取同一个估算值（由 `MinecraftServer.getAverageTickTime()` 推算 `min(20, 1000/mspt)`），附加 `note` 字段说明来源。
- `mspt`：仅 `values.last_1m.mean` 一个数值，附加 `note` 字段。
- `cpu`：`available` 为 `false`，无 `system` / `process`，附加 `note: "CPU 详细数据需安装 spark mod"`。
- `memory` / `gc` / `threads`：与 spark 可用时完全一致。

此外，spark 可用但某项统计取数抛异常时，对应子对象退化为 `{"available": false, "error": "<异常信息>"}`，客户端应先判 `available` 再读 `values`。

**错误：** 采集超时返回 504 `获取性能数据超时`；异常返回 500。

### `POST /api/v1/server/broadcast`

把外部渠道（当前是 QQ 群）的一句话投到全服公屏。由 `ChatBridgeHandlerImpl` 在服务器主线程执行，3 秒超时。

请求体：

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `sender` | string | 是 | 发言人展示名，最长 32 字符 |
| `content` | string | 是 | 正文，最长 256 字符 |
| `source` | string | 否 | 来源标签，缺省 `QQ`，最长 32 字符 |
| `channel` | string | 否 | 频道/群名，给出时前缀渲染为 `[来源\|频道]` |

四个字段都会先经 `ChatTextSanitizer` 清洗：**剥掉分节符 `§` 与全部控制字符（含换行、制表）后再按长度截断**。这是防伪装的关键——保留 `§` 意味着任何人都能把发言染成系统提示的颜色，保留换行则能撑出一段假公告。清洗后为空的字段按缺失处理。

```bash
curl -X POST "http://localhost:22222/api/v1/server/broadcast" \
  -H "X-API-Key: sk-your-token" -H "Content-Type: application/json" \
  -d '{"source":"QQ","channel":"世界树主群","sender":"Shinoyuki","content":"服务器十分钟后重启"}'
```

游戏内渲染为 `[QQ|世界树主群] Shinoyuki: 服务器十分钟后重启`，其中前缀灰色、发言人金色、正文白色。

响应：

```json
{
  "success": true,
  "data": {
    "recipients": 3,
    "rendered": "[QQ|世界树主群] Shinoyuki: 服务器十分钟后重启"
  },
  "message": "已发送到公屏",
  "code": 200
}
```

`recipients` 为收到消息的在线玩家数，为 0 表示服务器当前无人。主线程繁忙导致 3 秒内未执行时返回 **504**。

## 物品图标 API

### `GET /api/v1/item-icon`

公开端点（`<img>` 标签无法携带自定义头，故不鉴权）。从已加载的 mod jar 中抽取物品贴图 PNG。

**查询参数：**

- `id`（必需）：物品 id，形如 `minecraft:diamond_sword`。无冒号时首个 `/` 会被当作命名空间分隔符（`minecraft/diamond_sword` 等价）；无命名空间时默认 `minecraft`。字符白名单为 `[a-z0-9_.:/-]`，含 `..` 或越界字符返回 400。

**成功响应：** HTTP 200，`Content-Type: image/png`，`Cache-Control: public, max-age=86400`，响应体为 PNG 字节。

**解析顺序（best-effort）：**

1. `assets/<ns>/models/item/<path>.json` 的 `textures.layer0` 指向的贴图
2. 回退 `assets/<ns>/textures/item/<path>.png`
3. 回退 `assets/<ns>/textures/block/<path>.png`
4. 都没有则 404（前端应显示占位图）

**错误响应**（`ApiRouter` 风格的对象型 error）：

```json
{
  "success": false,
  "error": { "code": 404, "message": "未找到物品图标: minecraft:not_exist" },
  "timestamp": 1754103540217
}
```

命中结果与未命中结果都会按 id 缓存在内存中，避免重复扫描 jar。

## 线路接入 API

### `GET /api/v1/net/nodes`

返回各接入线路（frp 中转与家宽直连）的实时在线人数与连接地址，供面向玩家的线路自查页面使用。

**公开端点**，无需任何凭据。响应里只有人数与地址，不含玩家名单与客户端 IP。

线路归属按"玩家从哪个入口端口进来"判定：mod 内置的转发器为每条线路在本机监听一个入口端口，统一转发到 Minecraft 的真实端口，并以转发器连接 Minecraft 时占用的本地端口为键登记会话；玩家进服时按来源端口认领。这一判定不依赖玩家输入的域名，因此玩家改用 IP 直连也能正确归类。

**请求**

```bash
curl http://your-server:22222/api/v1/net/nodes
```

**响应**

```json
{
  "success": true,
  "message": "成功获取线路状态",
  "data": {
    "nodes": [
      {
        "id": "guangzhou",
        "name": "广州线",
        "endpoint": "guangzhou.mcwok.cn:25565",
        "probeUrl": "wss://guangzhou.mcwok.cn/probe",
        "online": 3,
        "connecting": 0
      },
      {
        "id": "xiamen",
        "name": "厦门联通专线",
        "endpoint": "home.shinoyuki.cn:25565",
        "probeUrl": "wss://home.shinoyuki.cn:8443/probe",
        "online": 8,
        "connecting": 1
      }
    ],
    "totalOnline": 11,
    "maxPlayers": 20,
    "unattributed": 0,
    "relayEnabled": true
  }
}
```

**字段说明**

| 字段 | 类型 | 说明 |
|------|------|------|
| `nodes[].id` | string | 线路标识，与配置中的 `network.nodes[].id` 对应 |
| `nodes[].name` | string | 展示名 |
| `nodes[].endpoint` | string | 给玩家填进游戏客户端的地址 |
| `nodes[].probeUrl` | string | WebSocket 延迟探针地址；空串表示该线路不做延迟探测 |
| `nodes[].online` | int | 当前经这条线在玩的人数 |
| `nodes[].connecting` | int | 已连上但尚未进入游戏的连接数。除正在登录的玩家外，还包含客户端刷新服务器列表产生的短连接，因此有秒级抖动，仅供参考 |
| `totalOnline` | int | 全服在线人数 |
| `maxPlayers` | int | 服务器人数上限 |
| `unattributed` | int | 未经转发器直连进来的玩家数，通常是内网直连 |
| `relayEnabled` | bool | 转发器是否已启用 |

未启用转发器（`network.enabled = false`）时，`nodes` 仍按配置原样返回，但所有 `online` 与 `connecting` 恒为 0，且 `relayEnabled` 为 `false`。

### 延迟探针协议

探针不是 HTTP 端点，而是独立监听的 WebSocket 服务（默认 `127.0.0.1:25610`，经 nginx 或 Caddy 终结 TLS 后以 `wss://<线路域名>/probe` 对外）。

探针必须部署在 Minecraft 所在的那台机器上，并经由与游戏流量完全相同的 frp 隧道暴露。若把探针放在 frps 一侧，测到的只是"玩家到中转节点"的半程，隧道抖动与家宽状况一概测不出来，数据反而会误导玩家选错线路。

协议只做回显：客户端发送文本帧，服务端原样发回。客户端把发送时刻编进消息，收回后相减即为往返耗时。服务端既不解析消息内容也不追加时间戳，以保证测出来的是纯网络往返而非服务端处理耗时。

约束：

- 帧载荷上限 1024 字节
- 不支持分片帧与二进制帧
- 客户端帧必须按 RFC 6455 掩码（浏览器原生 `WebSocket` 自动满足）
- 建立连接后 10 秒内未完成升级握手会被回收

**相关配置**（`config/Shinoyuki-Optimize/shinoyuki_accesshub/common.toml`）

```toml
[network]
enabled = false          # 转发器总开关, 需先配好 frp 与 DNS 再开启
bind-host = "0.0.0.0"    # 线路入口监听地址
minecraft-port = 25565   # 转发目标, 须与 server.properties 的 server-port 一致

[network.probe]
enabled = false
bind-host = "127.0.0.1"  # 对外一律经反代终结 TLS, 探针本身不直接暴露
port = 25610

[[network.nodes]]
id = "guangzhou"
display-name = "广州线"
listen-port = 25606      # 须与 frpc 配置里该线路 proxy 的 localPort 一致
endpoint = "guangzhou.mcwok.cn:25565"
probe-url = "wss://guangzhou.mcwok.cn/probe"
```

`display-name`、`endpoint`、`probe-url` 这类展示项改完后 `/accesshub reload` 即生效；入口端口在启动时绑定，增删线路或改 `listen-port` 需要重启服务器。

给已在运行的服务器升级 jar 时，`[network]` 段**不会**自动出现在 `common.toml` 里（完整默认配置只在文件不存在时写入），读取时静默回退到 `enabled = false`。需要手工把上面的配置段补进去。

完整的 frp、nginx、证书与 DNS 部署配置见 [deploy/network/README.md](deploy/network/README.md)。

## 识别码与 QQ 绑定 API

让管理员能从 QQ 群里直接执行运维命令的一条链路：管理员在面板签发 **64 位个人识别码**，私聊 QQ 机器人发送 `#绑定 <识别码>` 把 QQ 号认领到自己的面板账号；此后该 QQ 在任意群发出的 `#加白` / `#say` 等命令，都以该管理员身份执行。

设计要点：

- **只存哈希**。`admin_personal_codes` 表只有 SHA-256 与前 8 / 后 4 位掩码，明文仅在签发响应里出现一次。库被拖走也拿不到可用的码。
- **一人一码**。`admin_id` 即主键，重签直接覆盖，旧码立即失效；已建立的 QQ 绑定不受影响（绑定是既成事实，重签是为了让泄露的码作废）。
- **一 QQ 一主**。`admin_qq_bindings.qq` 唯一，同一管理员可绑多个 QQ（大小号），但一个 QQ 不能同时属于两个管理员。
- **停用即断通道**。查询绑定时连表带出 `admin_users.is_active`，管理员被停用后 `bound` 直接返回 false。

### `GET /api/v1/admin/personal-code`

查询**当前登录管理员**的识别码状态。必须携带管理员 JWT——用 X-API-Key 访问（或 `api.auth.enabled=false` 时）拿不到"我是谁"，一律返回 **401**。

```json
{
  "success": true,
  "data": {
    "issued": true,
    "codeLength": 64,
    "prefix": "K7xQ2m8v",
    "suffix": "9fLm",
    "issuedAt": "2026-08-14T20:31:05.412",
    "boundQq": ["10498851"]
  },
  "code": 200
}
```

从未签发过时 `issued` 为 false，且 `prefix` / `suffix` / `issuedAt` 三个键整个缺失。

### `POST /api/v1/admin/personal-code`

为当前管理员签发或重置识别码。无请求体。

```json
{
  "success": true,
  "data": {
    "code": "K7xQ2m8v...(共 64 位)...9fLm",
    "codeLength": 64,
    "message": "识别码只显示这一次, 请立即复制保存"
  },
  "message": "识别码已签发",
  "code": 200
}
```

码由 `SecureRandom` 取 48 字节经 base64url 无 padding 编码而成，恰好 64 字符，字符集 `[A-Za-z0-9_-]`。**明文不再有第二次获取机会**，丢失只能重签。

### `POST /api/v1/bot/bind`

凭识别码把 QQ 号认领到对应管理员。供 Bot 后台调用（`X-API-Key`）。

```json
{ "code": "K7xQ2m8v...9fLm", "qq": "10498851" }
```

| 状态码 | 含义 |
|--------|------|
| 200 | 绑定成功；若该 QQ 早已绑定同一管理员则 `data.alreadyBound` 为 true（幂等） |
| 400 | 缺少参数，或 QQ 号格式非法（须 5-15 位数字且不以 0 开头） |
| 403 | 识别码无效（不存在、格式错），或目标管理员账号已停用 |
| 409 | 该 QQ 已绑定到**其他**管理员，需先解绑 |

格式错与码不存在都回同一句"识别码无效"，避免成为探测口径。

```json
{
  "success": true,
  "data": {
    "qq": "10498851",
    "adminId": 1,
    "adminUsername": "admin",
    "adminDisplayName": "超级管理员",
    "boundAt": "2026-08-14T20:35:12.008",
    "alreadyBound": false
  },
  "message": "绑定成功",
  "code": 200
}
```

### `GET /api/v1/bot/binding?qq={qq}`

查某 QQ 的绑定。Bot 在执行**每一条**运维命令前都调用它复核发令人身份，不依赖本地缓存。

```json
{
  "success": true,
  "data": {
    "qq": "10498851",
    "bound": true,
    "adminActive": true,
    "adminId": 1,
    "adminUsername": "admin",
    "adminDisplayName": "超级管理员",
    "boundAt": "2026-08-14T20:35:12.008"
  },
  "code": 200
}
```

未绑定时 `bound` 为 false 且不含管理员字段。管理员账号被停用时 `bound` 同样为 false（另附 `adminActive: false`），使调用方只看 `bound` 一个字段即可决定放行与否。

### `DELETE /api/v1/bot/binding?qq={qq}`

解除绑定，直接删行不留 tombstone。已解除返回 200，该 QQ 本就未绑定返回 **404**。

配套的公屏发言端点见 [`POST /api/v1/server/broadcast`](#post-apiv1serverbroadcast)。

## 整合包分发 API

服务端维护整合包的版本与文件清单，启动器（Aurora）据此同步客户端文件，服务端还可以在进服时按版本拦截未更新的玩家。

数据模型：

- **版本**有三种状态：`draft`（草稿，可编辑）→ `published`（当前发布，全局**最多一个**）→ `archived`（曾经发布过，已被替换）。已发布与已归档的版本不可修改，要改只能复制成新草稿。
- **条目**是版本里的一个文件，`kind` 取 `platform`（来自 Modrinth / CurseForge，带平台元数据）或 `custom`（自研文件，通常经上传端点进 OSS）。
- **同步策略** `policy` 取 `managed` / `seeded` / `optional`。服务端只校验取值并原样下发，具体含义由启动器解释。

公开端点与管理端点的鉴权、响应包装都不同，见[认证系统](#认证系统)与[整合包端点的两处例外](#整合包端点的两处例外)。

### `GET /api/v1/pack/latest`

公开端点。返回当前发布版本的指针，响应头 `Cache-Control: no-cache, no-store`。

```json
{
  "pack_id": "wok",
  "version": "1.4.2",
  "manifest_url": "https://api.mcwok.cn/api/v1/pack/manifest/1.4.2",
  "released_at": "2026-08-23T09:15:42Z",
  "note": "修复若干模组冲突",
  "min_launcher_version": "0.1.0"
}
```

| 字段 | 说明 |
|------|------|
| `pack_id` | 恒为 `wok` |
| `version` | 当前发布版本号 |
| `manifest_url` | 清单地址。**前缀 `https://api.mcwok.cn/api/v1/pack/manifest/` 写死在代码里**，与本实例实际的监听地址无关 |
| `released_at` | 首次发布时间，ISO-8601 UTC。回滚回来的版本仍显示它最初的发布时间 |
| `note` | 更新说明，未填时为 `null` |
| `min_launcher_version` | 恒为 `0.1.0` |

没有任何已发布版本时返回 404 `No published pack version`。

### `GET /api/v1/pack/manifest/{version}`

公开端点。返回指定版本的完整文件清单，只对 `published` 与 `archived` 状态的版本可见，草稿返回 404。

已发布版本不可变，因此成功响应带 `Cache-Control: public, max-age=31536000, immutable`；404 响应带 `no-store`。

```json
{
  "schema": 1,
  "pack_id": "wok",
  "version": "1.4.2",
  "minecraft": "1.20.1",
  "loader": { "kind": "forge", "version": "47.4.20" },
  "files": [
    {
      "path": "mods/example-1.0.0.jar",
      "sha1": "3f786850e387550fdab836ed7e6dc881de23001b",
      "size": 482133,
      "policy": "managed",
      "urls": [
        "https://cdn.example.com/wok/files/3f/78/3f786850e387550fdab836ed7e6dc881de23001b",
        "https://example-bucket.oss-cn-hangzhou.aliyuncs.com/files/3f/78/3f786850e387550fdab836ed7e6dc881de23001b"
      ]
    }
  ]
}
```

- `files` 按 `path` 升序。
- `urls` 按优先级排列，客户端应依次尝试并以 `sha1` 校验。条目的下载地址以 `pack.oss.public-base-url`（自有 CDN）开头时，服务端会追加一个把前缀换成 OSS 直连地址的兜底项；其余条目（如仍指向平台 CDN 的）只有一个地址。未配置 `pack.oss` 或 CDN 基址与 OSS 直连地址相同时不追加。兜底地址由配置推导、不入库，这三项配置在启动时读取，改动后需重启。
- `{version}` 须匹配 `[0-9A-Za-z][0-9A-Za-z._+-]{0,127}`。不匹配时该路径不再是公开端点：无凭据返回 401，带 JWT 返回 404。

### 管理端点的通用约定

以下端点全部**只认管理员 JWT**。

- 请求体必须是 JSON 对象，且**不允许出现未列出的字段**，否则 400 `请求体包含未知字段: <名字>`。
- 路径里的 `{id}` 必须是正整数，否则 400。
- 时间字段 `createdAt` / `publishedAt` 是 **Unix 秒**（数值），不是 ISO 字符串。

**版本对象：**

```json
{
  "id": 12,
  "version": "1.4.3",
  "status": "draft",
  "minecraft": "1.20.1",
  "loaderKind": "forge",
  "loaderVersion": "47.4.20",
  "note": null,
  "createdAt": 1787476542,
  "publishedAt": null
}
```

**条目对象：**

```json
{
  "id": 340,
  "versionId": 12,
  "path": "mods/example-1.0.0.jar",
  "kind": "platform",
  "policy": "managed",
  "sha1": "3f786850e387550fdab836ed7e6dc881de23001b",
  "size": 482133,
  "downloadUrl": "https://cdn.modrinth.com/data/AbCdEfGh/versions/IjKlMnOp/example-1.0.0.jar",
  "platform": "modrinth",
  "projectId": "AbCdEfGh",
  "projectName": "Example Mod",
  "externalVersionId": "IjKlMnOp"
}
```

**字段校验规则**（新建与修改共用）：

| 字段 | 规则 |
|------|------|
| `version` / `minecraft` / `loaderVersion` | `[0-9A-Za-z][0-9A-Za-z._+-]{0,127}`；`version` 全局唯一，重复返回 409 |
| `loaderKind` | `fabric` / `quilt` / `forge` / `neoforge` |
| `path` | 使用正斜杠的相对路径；不得含空段、`.`、`..`、控制字符、`<>:"\|?*`、Windows 保留设备名（`CON`、`NUL`、`COM1` 等）、DOS 短文件名（如 `PROGRA~1`），段尾不得是空格或点；顶层目录不得是 `.aurora` / `saves` / `screenshots` / `logs`。同一版本内路径按**不区分大小写**判重，重复返回 409 |
| `kind` | `platform` / `custom` |
| `policy` | `managed` / `seeded` / `optional` |
| `sha1` | 40 位小写十六进制 |
| `size` | 非负整数（字节） |
| `downloadUrl` | 带主机名的 `http` / `https` 绝对地址，不得含用户凭据或 `#` 片段 |
| `platform` / `projectId` / `projectName` / `externalVersionId` | `kind = platform` 时四项全部必填，`platform` 只能是 `modrinth` 或 `curseforge`；`kind = custom` 时四项都**不得出现**（或为 `null`） |

**错误映射：**

| HTTP | 场景 |
|------|------|
| 400 | 请求体非法、字段校验失败、未知字段、ID 非正整数 |
| 401 | 未携带有效 JWT（含只带 `X-API-Key` 的情况） |
| 404 | 版本或条目不存在 |
| 409 | 版本号重复、路径重复、目标不是草稿、差异修订号已过期、存在移除项但未确认、并发修改冲突 |
| 500 | 数据库故障等内部错误，消息恒为 `整合包管理操作失败` |
| 503 | 服务未就绪（路由器风格的对象型 `error`） |

### `GET /api/v1/pack/versions`

返回全部版本（含草稿）的版本对象数组，按创建时间倒序。

### `POST /api/v1/pack/versions`

新建草稿，成功返回 HTTP 201 与版本对象。两种用法二选一：

**全新草稿**——`version`、`minecraft`、`loaderKind`、`loaderVersion` 必填，`note` 可选：

```json
{
  "version": "1.4.3",
  "minecraft": "1.20.1",
  "loaderKind": "forge",
  "loaderVersion": "47.4.20",
  "note": "新增两个模组"
}
```

**从已有版本复制**——只能提交 `version` 与 `copyFromVersionId`，其余字段必须缺席，否则 400：

```json
{ "version": "1.4.3", "copyFromVersionId": 11 }
```

复制会带上源版本的 Minecraft 版本、加载器信息、更新说明与**全部条目**。源版本可以是任意状态，这是修改已发布版本的唯一途径。

### `PUT /api/v1/pack/versions/{id}`

整体替换草稿的版本信息。`version`、`minecraft`、`loaderKind`、`loaderVersion` 全部必填；`note` 省略即清空。

- 目标不是草稿返回 409 `已发布或归档版本不可修改，请先复制为新草稿`。
- 草稿里已有 `platform` 条目时，`minecraft` / `loaderKind` / `loaderVersion` 不能再改（平台文件与游戏版本、加载器绑定）。此时返回 409，但消息是笼统的 `草稿版本已被其他操作修改或删除`，排查时注意区分。

### `GET /api/v1/pack/versions/{id}/entries`

返回该版本的条目对象数组，按 `path` 升序。对任意状态的版本可用。

### `POST /api/v1/pack/versions/{id}/entries`

向草稿添加一个条目，成功返回 HTTP 201 与条目对象。

```json
{
  "path": "mods/example-1.0.0.jar",
  "kind": "platform",
  "policy": "managed",
  "sha1": "3f786850e387550fdab836ed7e6dc881de23001b",
  "size": 482133,
  "downloadUrl": "https://cdn.modrinth.com/data/AbCdEfGh/versions/IjKlMnOp/example-1.0.0.jar",
  "platform": "modrinth",
  "projectId": "AbCdEfGh",
  "projectName": "Example Mod",
  "externalVersionId": "IjKlMnOp"
}
```

### `PUT /api/v1/pack/entries/{id}`

整体替换草稿中的一个条目，请求体与添加相同。所属版本不是草稿时返回 409。

### `DELETE /api/v1/pack/entries/{id}`

删除草稿中的一个条目。

```json
{
  "success": true,
  "data": { "id": 340, "deleted": true },
  "message": "整合包条目删除成功",
  "code": 200,
  "timestamp": "2026-08-23T17:15:42.108"
}
```

### `POST /api/v1/pack/versions/{id}/upload`

上传一个自研文件到阿里云 OSS，并把它登记为该草稿的 `custom` 条目。需要 `pack.oss.enabled = true` 且 OSS 配置完整。

请求为 `multipart/form-data`，**有且仅有**以下三个字段，多出任何字段都返回 400：

| 字段 | 类型 | 说明 |
|------|------|------|
| `file` | 文件 | 单个文件，上限 **200 MB** |
| `path` | 文本 | 条目路径，规则同上表，UTF-8，最长 4096 字节 |
| `policy` | 文本 | `managed` / `seeded` / `optional` |

```bash
curl -X POST "http://localhost:22222/api/v1/pack/versions/12/upload" \
  -H "Authorization: Bearer eyJhbGciOi..." \
  -F "file=@./config/wok-client.toml" \
  -F "path=config/wok-client.toml" \
  -F "policy=seeded"
```

处理过程：服务端计算文件 SHA-1，以**内容寻址**的对象键 `files/<sha1 前 2 位>/<第 3-4 位>/<sha1>` 写入 OSS，再以 `pack.oss.public-base-url` 加对象键作为 `downloadUrl` 写入条目。相同内容的文件重复上传会落到同一个对象上。

成功返回 HTTP 201，`data` 为条目对象（`kind` 恒为 `custom`，四个平台字段为 `null`）。

| HTTP | 场景 |
|------|------|
| 400 | 非 multipart 请求、字段缺失或重复、多余字段、`path` / `policy` 非法 |
| 404 | 版本不存在 |
| 409 | 版本不是草稿，或路径与已有条目重复 |
| 413 | 文件或请求超过 200 MB |
| 502 | OSS 返回失败，或上传被中断 |
| 503 | OSS 上传未启用 / 配置无效、上传队列已满、服务正在关闭 |
| 504 | OSS 上传超时（单次上限 15 分钟） |

注意事项：

- 同时最多处理 2 个上传，另可排队 2 个，再多直接 503 `文件上传队列已满，请稍后重试`。
- 文件是**先传 OSS、后写条目**。写条目失败（404 / 409）时对象已经留在 OSS 上；由于是内容寻址，它不会污染任何版本，重试时也会复用同一对象。
- OSS 配置在每次上传时读取，改完 `common.toml` 后 `/accesshub reload` 即可，无需重启。

### `GET /api/v1/pack/versions/{id}/diff`

计算该版本相对**当前发布版本**的差异，用于发布或回滚前的人工审阅。

```json
{
  "success": true,
  "data": {
    "revision": "9b74c9897bac770ffc029102a200c5de1f0c5a3f7d4e9a1b6c8d2e4f60718293",
    "publishedVersion": { "id": 11, "version": "1.4.2", "status": "published", "...": "..." },
    "targetVersion": { "id": 12, "version": "1.4.3", "status": "draft", "...": "..." },
    "added": [ { "id": 341, "path": "mods/new-mod-2.0.0.jar", "...": "..." } ],
    "removed": [ { "id": 298, "path": "mods/old-mod-1.2.0.jar", "...": "..." } ],
    "changed": [
      {
        "before": { "id": 300, "path": "mods/example-1.0.0.jar", "...": "..." },
        "after": { "id": 340, "path": "mods/example-1.0.0.jar", "...": "..." },
        "changedFields": ["sha1", "size", "downloadUrl", "externalVersionId"]
      }
    ]
  },
  "message": "整合包版本差异获取成功",
  "code": 200,
  "timestamp": "2026-08-23T17:15:42.108"
}
```

上例中的 `"...": "..."` 是文档省略记号，实际返回完整的版本对象与条目对象。

- `revision` 是对"当前发布版本 + 目标版本 + 双方全部条目"计算的 SHA-256，64 位小写十六进制。任何一方发生变化，修订号都会变。
- `publishedVersion` 在尚无发布版本时为 `null`，此时目标的全部条目都算 `added`。
- 条目按 `path` 精确匹配。`changedFields` 的取值范围：`kind`、`policy`、`sha1`、`size`、`downloadUrl`、`platform`、`projectId`、`projectName`、`externalVersionId`。
- 目标版本与当前发布版本之间若存在**仅大小写不同**的路径，返回 409 `单次版本切换不能只修改路径大小写`（Windows 客户端无法安全完成这种改名）。

### `POST /api/v1/pack/versions/{id}/publish`

把草稿发布为当前版本。

```json
{
  "expectedDiffRevision": "9b74c9897bac770ffc029102a200c5de1f0c5a3f7d4e9a1b6c8d2e4f60718293",
  "confirmRemovals": true
}
```

| 字段 | 必需 | 说明 |
|------|------|------|
| `expectedDiffRevision` | 是 | 刚从 `diff` 端点取到的 `revision` |
| `confirmRemovals` | 否 | 缺省 `false`。差异中存在 `removed` 条目时必须为 `true` |

整个切换在一个事务里完成：服务端重新计算差异并比对修订号，一致才执行，从而保证"发布出去的正是审阅过的那一份"。成功后原发布版本转为 `archived`，目标转为 `published` 并写入 `publishedAt`，响应 `data` 是更新后的版本对象。

| 409 消息 | 含义 |
|----------|------|
| `整合包差异已变化，请重新获取并审阅后再执行版本切换` | 取 `diff` 之后草稿或当前发布版本又变了，重新取 `diff` |
| `发布将移除 N 个条目，必须显式确认` | 有移除项而 `confirmRemovals` 不是 `true` |
| `已发布或归档版本不可修改，请先复制为新草稿` | 目标不是草稿 |
| `草稿版本已被其他操作修改，发布未生效` | 并发冲突 |

### `POST /api/v1/pack/versions/{id}/rollback`

把当前发布版本切回某个**已归档**的版本。请求体与 `publish` 完全相同，同样需要先对目标版本取 `diff`。

目标必须是曾经发布过的归档版本，否则 409 `只能回滚到曾经发布过的归档版本`。回滚不改写目标的 `publishedAt`。

### 典型发布流程

```bash
JWT="eyJhbGciOi..."
BASE="http://localhost:22222/api/v1"

# 1. 从当前发布版本复制出草稿
curl -X POST "$BASE/pack/versions" -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"version":"1.4.3","copyFromVersionId":11}'

# 2. 增删改条目、上传自研文件（略）

# 3. 取差异，人工审阅 added / removed / changed
curl "$BASE/pack/versions/12/diff" -H "Authorization: Bearer $JWT"

# 4. 带上审阅过的修订号发布
curl -X POST "$BASE/pack/versions/12/publish" -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"expectedDiffRevision":"<第 3 步的 revision>","confirmRemovals":true}'
```

### 进服版本门控

版本门控不是 HTTP 端点，而是登录流程里的一道检查，由 `pack.version-gate.enabled` 控制，默认关闭。

- **版本怎么上报**：客户端装有本 mod 时，会在 Forge 登录握手的通道版本表里携带通道 `shinoyuki_accesshub:pack_version`，其版本值取自 JVM 系统属性 `shinoyuki.accesshub.pack-version`（由启动器在启动参数里传入）。
- **服务端怎么判**：与数据库里当前 `published` 版本的版本号做**字符串全等**比较。发布新版本后门控立即按新版本生效，无需改配置。
- **开启后一律拒绝的情况**：客户端未上报版本（没装本 mod、纯原版客户端、启动器没传参数）、版本不一致、服务端尚无已发布版本、门控查询本身出错。即门控是 fail-closed 的。
- **拒绝文案**取自 `pack.version-gate.reject-message`，支持 `{current}`（客户端版本）与 `{required}`（要求版本）占位符。
- **紧急放行**：把 `pack.version-gate.enabled` 改为 `false` 后执行 `/accesshub reload`，立即生效。

客户端上报的版本可以伪造，门控只是防止玩家忘记更新的提示机制，**不是安全凭据**。

**相关配置**（`config/Shinoyuki-Optimize/shinoyuki_accesshub/common.toml`）

```toml
[pack.version-gate]
enabled = false
reject-message = "&c整合包版本不匹配\n&7当前版本: {current}\n&7所需版本: {required}\n&7请使用 Aurora 更新后重试"

[pack.oss]
enabled = false                 # 自研文件上传总开关
endpoint = ""                   # 地域 Endpoint, 如 https://oss-cn-hangzhou.aliyuncs.com, 不含 bucket
bucket = ""
access-key-id = ""              # 建议用仅授予目标 bucket 写权限的 RAM 子账号
access-key-secret = ""
public-base-url = ""            # 客户端下载基地址, 可含 CDN 路径前缀, 如 https://cdn.example.com/wok
```

`pack.*` 这些键在升级 jar 后会由服务端自动补进已有的 `common.toml`。

## UUID 自动补充机制

### 设计理念

白名单采用**"玩家名优先，UUID 后补"**策略：

1. **添加阶段**：管理员只需提供玩家名即可加白，数据库中 `uuid` 列为 NULL
2. **登录阶段**：玩家首次登录时，`PlayerLoginListener` 调用 `WhitelistManager.updatePlayerUuid` 自动补齐 UUID
3. **持久化**：系统运行在纯数据库模式，无 JSON 文件同步环节

### 工作流程

```mermaid
sequenceDiagram
    participant Admin as 管理员
    participant API as API接口
    participant DB as 数据库
    participant Player as 玩家
    participant Listener as 登录监听器

    Admin->>API: POST /api/v1/whitelist {"name": "PlayerName", "source": "ADMIN"}
    API->>DB: INSERT (name, uuid=NULL)
    API->>Admin: 201 {"added": true, "uuid_pending": true}

    Player->>Listener: 玩家登录服务器
    Listener->>DB: 按玩家名查白名单
    Listener->>DB: UPDATE uuid WHERE name = PlayerName
    Listener->>Player: 放行并发送欢迎消息
```

### 数据库状态变化

**添加时：**

```
id | name       | uuid | source | is_active
1  | PlayerName | NULL | ADMIN  | 1
```

**首次登录后：**

```
id | name       | uuid                                 | source | is_active
1  | PlayerName | 550e8400-e29b-41d4-a716-446655440000 | ADMIN  | 1
```

### 适用范围

- 仅 `POST /api/v1/whitelist`（单条添加）走此流程。
- `POST /api/v1/whitelist/batch` 的 `add` 操作**立即写入生成的 UUID**，不走登录补充，详见批量端点说明。

## 错误代码说明

| HTTP 状态码 | 说明 | 常见来源 |
|------------|------|----------|
| 200 | 成功 | - |
| 201 | 创建成功 | `POST /api/v1/whitelist`；整合包新建草稿、添加条目、上传文件 |
| 207 | 批量操作部分成功 | `POST /api/v1/whitelist/batch` |
| 400 | 请求参数错误 | 缺少必需字段、格式非法、来源类型无效、整合包请求体含未知字段 |
| 401 | 认证失败 | 缺少/错误的 `X-API-Key` 或 JWT；管理员登录凭据错误；对仅 JWT 端点使用 API 令牌 |
| 403 | 拒绝 | `POST /api/v1/bot/bind` 的识别码无效或目标管理员已停用 |
| 404 | 资源不存在 | 玩家条目不存在、玩家不在线、整合包版本/条目不存在、路径无对应路由 |
| 405 | 方法不支持 | 使用了 GET / POST / PUT / DELETE / OPTIONS 之外的方法 |
| 409 | 冲突 | 玩家已在白名单；玩家认证未启用而请求发码；QQ 已绑定其他管理员；整合包版本号或路径重复、目标不是草稿、差异修订号过期 |
| 413 | 请求体过大 | 整合包上传文件超过 200 MB |
| 429 | 并发超限 | `/api/v1/player` 并发查询超过 5 个 |
| 500 | 服务器内部错误 | 未捕获异常、数据库故障 |
| 502 | 上游失败 | 整合包上传时 OSS 返回失败或上传被中断 |
| 503 | 依赖组件未就绪 | 对应 handler 尚未初始化；玩家认证系统未就绪；OSS 上传未启用或队列已满 |
| 504 | 主线程/采集/上游超时 | `/api/v1/player`、`/api/v1/server/*`；整合包上传 OSS 超时 |

> 再次提醒：部分端点响应体中的 `code` 字段与 HTTP 状态码不一致（见[响应格式](#响应格式)），请以 HTTP 状态码为准。

## 安全最佳实践

### API 令牌管理

- 令牌明文存放在服务端 `common.toml`，请限制该文件的读取权限
- 不要在前端代码里硬编码令牌；浏览器侧应走管理员 JWT
- 轮换令牌：修改 `[api.auth] api-token` 后执行 `/accesshub reload` 即生效（令牌在每次请求时从配置读取）；置空则下次启动自动重新生成

### JWT 安全

- 有效期 24 小时，过期后需重新登录
- 修改 `[api.auth] jwt-secret` 并**重启服务端**后，所有已签发 token 失效，可作为紧急吊销手段。签名密钥在启动时载入，仅 `/accesshub reload` 不会替换它
- 避免在 URL 中传递 token

### 网络安全

- API 默认监听 `0.0.0.0:22222` 且 CORS 默认允许全部来源（`api.cors.allowed-origins = ["*"]`），公网部署务必用防火墙或反向代理收口
- 需要 HTTPS 时在前置反向代理终结，mod 本身不提供 TLS
- 反代场景下服务端会读取 `X-Forwarded-For` / `X-Real-IP` 记录操作者 IP，请确保这两个头由可信代理设置

## 使用示例

### 白名单管理示例

```bash
# 1. 获取白名单列表
curl -X GET "http://localhost:22222/api/v1/whitelist?page=1&size=20" \
  -H "X-API-Key: sk-your-api-token-here"

# 2. 添加白名单条目（只需玩家名 + 来源）
curl -X POST http://localhost:22222/api/v1/whitelist \
  -H "Content-Type: application/json" \
  -H "X-API-Key: sk-your-api-token-here" \
  -d '{
    "name": "NewPlayer",
    "source": "ADMIN"
  }'

# 3. 添加白名单条目（完整参数）
curl -X POST http://localhost:22222/api/v1/whitelist \
  -H "Content-Type: application/json" \
  -H "X-API-Key: sk-your-api-token-here" \
  -d '{
    "name": "NewPlayer",
    "source": "ADMIN",
    "added_by_name": "AdminUser",
    "added_by_uuid": "API",
    "qq": "10001"
  }'

# 4. 按 UUID 删除
curl -X DELETE http://localhost:22222/api/v1/whitelist/550e8400-e29b-41d4-a716-446655440000 \
  -H "X-API-Key: sk-your-api-token-here"

# 5. 按玩家名删除（UUID 未补充时用这个）
curl -X DELETE http://localhost:22222/api/v1/whitelist/by-name/NewPlayer \
  -H "X-API-Key: sk-your-api-token-here"

# 6. 获取白名单统计
curl -X GET http://localhost:22222/api/v1/whitelist/stats \
  -H "X-API-Key: sk-your-api-token-here"

# 7. 为已加白玩家重新签发注册码
curl -X POST http://localhost:22222/api/v1/whitelist/regcode \
  -H "Content-Type: application/json" \
  -H "X-API-Key: sk-your-api-token-here" \
  -d '{"name": "NewPlayer"}'
```

### 启用/禁用与批量操作

```bash
# 禁用某玩家的访问权限（保留在白名单，进服被拒并提示已被管理员关闭）
curl -X PUT http://localhost:22222/api/v1/whitelist/by-name/Player1/status \
  -H "Content-Type: application/json" \
  -H "X-API-Key: sk-your-api-token-here" \
  -d '{"is_active": false}'

# 重新启用
curl -X PUT http://localhost:22222/api/v1/whitelist/by-name/Player1/status \
  -H "Content-Type: application/json" \
  -H "X-API-Key: sk-your-api-token-here" \
  -d '{"is_active": true}'

# 批量添加
curl -X POST http://localhost:22222/api/v1/whitelist/batch \
  -H "Content-Type: application/json" \
  -H "X-API-Key: sk-your-api-token-here" \
  -d '{
    "operation": "add",
    "source": "ADMIN",
    "added_by_name": "AdminUser",
    "players": [
      {"name": "Player1"},
      {"name": "Player2"},
      {"name": "Player3"}
    ]
  }'

# 批量删除
curl -X POST http://localhost:22222/api/v1/whitelist/batch \
  -H "Content-Type: application/json" \
  -H "X-API-Key: sk-your-api-token-here" \
  -d '{
    "operation": "remove",
    "players": [
      {"uuid": "550e8400-e29b-41d4-a716-446655440000"},
      {"uuid": "550e8400-e29b-41d4-a716-446655440001"}
    ]
  }'

# 批量禁用
curl -X POST http://localhost:22222/api/v1/whitelist/batch \
  -H "Content-Type: application/json" \
  -H "X-API-Key: sk-your-api-token-here" \
  -d '{
    "operation": "disable",
    "players": [
      {"name": "Player1"},
      {"name": "Player2"}
    ]
  }'
```

### 管理员登录与 JWT 调用

```bash
# 1. 登录取 JWT
curl -X POST http://localhost:22222/api/v1/admin/login \
  -H "Content-Type: application/json" \
  -d '{"username": "admin", "password": "your-password"}'

# 2. 用 JWT 查询当前管理员（此端点不接受 X-API-Key）
curl -X GET http://localhost:22222/api/v1/admin/me \
  -H "Authorization: Bearer eyJhbGciOi..."

# 3. 生成管理员注册令牌（X-API-Key 或 JWT 均可）
curl -X POST http://localhost:22222/api/v1/admin/generate-token \
  -H "Content-Type: application/json" \
  -H "X-API-Key: sk-your-api-token-here" \
  -d '{"expiryHours": 24}'
```

### 玩家数据与监控

```bash
# 查询在线玩家数据
curl -X GET "http://localhost:22222/api/v1/player?name=PlayerName" \
  -H "X-API-Key: sk-your-api-token-here"

# 查询离线玩家数据
curl -X GET "http://localhost:22222/api/v1/player?name=PlayerName&includeOffline=true" \
  -H "X-API-Key: sk-your-api-token-here"

# 在线玩家列表
curl -X GET http://localhost:22222/api/v1/server/players \
  -H "X-API-Key: sk-your-api-token-here"

# 服务器性能
curl -X GET http://localhost:22222/api/v1/server/performance \
  -H "X-API-Key: sk-your-api-token-here"

# 操作日志（最近 50 条 ADD）
curl -X GET "http://localhost:22222/api/v1/logs/operations?type=ADD&limit=50" \
  -H "X-API-Key: sk-your-api-token-here"

# 物品图标（公开，无需认证）
curl -X GET "http://localhost:22222/api/v1/item-icon?id=minecraft:diamond_sword" -o icon.png
```

### JavaScript (Fetch API)

```javascript
// 获取在线玩家列表
fetch('http://your-server:22222/api/v1/server/players', {
  headers: { 'X-API-Key': 'sk-your-api-token-here' }
})
  .then(response => response.json())
  .then(data => {
    if (data.success) {
      console.log('在线玩家数:', data.data.count);
    }
  });

// 获取性能数据
fetch('http://your-server:22222/api/v1/server/performance', {
  headers: { 'X-API-Key': 'sk-your-api-token-here' }
})
  .then(response => response.json())
  .then(data => {
    if (data.success && data.data.tps.available) {
      console.log('近 1 分钟 TPS:', data.data.tps.values.last_1m);
      // CPU 是 0..1 的比例, 展示成百分比需要 *100
      if (data.data.cpu.available) {
        console.log('进程 CPU:', (data.data.cpu.process.last_1m * 100).toFixed(2) + '%');
      }
    }
  });
```

### Python (requests)

```python
import requests

BASE = 'http://your-server:22222/api/v1'
headers = {'X-API-Key': 'sk-your-api-token-here'}

# 获取在线玩家
response = requests.get(f'{BASE}/server/players', headers=headers)
data = response.json()
if data['success']:
    print(f"在线玩家数: {data['data']['count']}")

# 添加白名单
def add_player_to_whitelist(player_name, source="ADMIN"):
    payload = {"name": player_name, "source": source}
    response = requests.post(f'{BASE}/whitelist', json=payload, headers=headers)
    return response.status_code, response.json()
```

## CORS 支持

`api.cors.enabled` 默认 `true`，`api.cors.allowed-origins` 默认 `["*"]`。响应头由 `HttpServer` 统一附加：

```
Access-Control-Allow-Origin: *
Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS
Access-Control-Allow-Headers: Content-Type, Authorization, X-API-Key, X-Requested-With
Access-Control-Max-Age: 3600
```

`OPTIONS` 预检请求在进入路由前直接返回 200。

## 缓存说明

服务端**没有** HTTP 响应级缓存，每次请求都会实时查询。实际存在的缓存只有两处：

- `WhitelistManager` 的进程内白名单缓存，用于登录校验，不影响 API 查询（API 直接读库）
- `ItemIconHandler` 的 PNG 字节缓存（含未命中负缓存），并对客户端下发 `Cache-Control: public, max-age=86400`

另有两个端点只是指示**客户端与中间代理**如何缓存，服务端自身不缓存：`GET /api/v1/pack/latest` 下发 `no-cache, no-store`；`GET /api/v1/pack/manifest/{version}` 成功时下发 `public, max-age=31536000, immutable`（已发布版本的清单不可变）。若在前面挂了 CDN 或反代缓存，请确认它遵守这两个头，否则启动器可能读到过期的发布指针。

## Spark 集成

安装 spark mod 后，`/api/v1/server/performance` 会经 `me.lucko.spark.api` 提供精确的 TPS / MSPT / CPU 数据。spark 未安装时自动降级：TPS / MSPT 由 `MinecraftServer.getAverageTickTime()` 估算，CPU 数据缺失（`available: false`），内存 / GC / 线程始终由 JVM MXBean 提供，不受影响。

## 版本信息

- **mod id**: `shinoyuki_accesshub`
- **mod 版本**: 0.5.4
- **API 版本**: v1
- **数据库结构版本**: 10
- **运行环境**: Minecraft 1.20.1 + Forge 47.4.20（`[47,)`）
- **可选依赖**: spark（性能数据精度）

## 常见问题

**Q: 为什么带上 `Authorization: Bearer sk-...` 会 401？**
A: `Authorization: Bearer` 只走 JWT 校验分支。API 令牌必须放在 `X-API-Key` 头里。

**Q: 为什么 `/api/v1/health`、`/api/v1/server/status`、`/api/v1/players/list` 全是 404？**
A: 这些端点在当前 Forge 实现中不存在，是旧文档遗留。在线玩家请用 `/api/v1/server/players`，性能请用 `/api/v1/server/performance`。

**Q: `POST /api/v1/whitelist` 传 `"source": "API"` 为什么 400？**
A: `WhitelistEntry.Source` 枚举只有 `PLAYER` / `ADMIN` / `SYSTEM`。若要标记调用渠道，请用 `added_by_uuid` 字段（如 `API` / `WEBUI`）。

**Q: 为什么白名单列表里有的条目没有 `uuid` 键？**
A: 该玩家的 UUID 尚未补充（值为 null，Gson 省略了该键），会在首次登录时自动补齐。

**Q: 为什么 CPU 使用率看起来只有 0.006？**
A: `cpu.system` / `cpu.process` 是 0..1 的比例，`0.006` 即 0.6%，展示时需要乘以 100。

**Q: 查询玩家数据为什么会超时或 429？**
A: 玩家数据必须在 Minecraft 主线程采集。在线查询超时 3 秒、离线 5 秒；并发上限 5，超出返回 429。

**Q: 为什么日志统计里有时没有 `set_active`、`reset_auth` 这些键？**
A: 统计端点按库里实际存在的 `operation_type` 聚合，所选时间范围内一条都没有的类型不会出现，按 0 处理即可。

**Q: 整合包管理端点带了正确的 `X-API-Key`，为什么还是 401？**
A: `/api/v1/pack/` 下除 `latest` 与 `manifest/{version}` 两个公开 GET 外，全部只认管理员 JWT，路由层会直接拒绝 API 令牌。先 `POST /api/v1/admin/login` 取 JWT。

**Q: 整合包发布为什么返回 409 "差异已变化"？**
A: `expectedDiffRevision` 必须是对**当前状态**取到的修订号。取 `diff` 之后只要草稿或当前发布版本有任何变动，修订号就会失效，重新取一次 `diff` 并审阅后再发布。

**Q: 升级 jar 之后线路统计一直是 0、`relayEnabled` 是 `false`？**
A: `[network]` 配置段只在 `common.toml` 不存在时生成，升级不会自动补。手工把该段加进配置文件并重启。

---

*本文档依据 `src/main/java/com/shinoyuki/accesshub/` 下的源码校准。若实现变更而文档未同步，请以 `api/ApiRouter.java` 的路由分支与各 Controller 的实现为准。*
