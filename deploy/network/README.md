# Minecraft 服务器多线路接入 —— 部署说明与现网记录

本目录是多线路接入方案的基础设施配置。玩家可以从七条彼此独立的线路进入同一个
Minecraft 服务端：六条经云节点 frp 中转，一条家宽直连。任意一条挂掉不影响其余。

配置按 frp **v0.70.1** 的 TOML 字段名编写，最低要求 v0.52
（v0.52 是 ini 转 TOML 的分水岭，字段名整体重命名过，低于此版本无法使用本目录的文件）。

mod 侧的对应功能（转发器、探针、`GET /api/v1/net/nodes`）见仓库根目录的 `API.md`「线路接入 API」一节。

> 本文档分两类内容：「现网状态」与「接入前的现网勘察」两节是带日期的**记录**，写的是当时观测到的事实；
> 第一节之后是**方案与操作说明**。记录不会随时间自动更新，照着它动手前请先复核现状。

---

## 现网状态（2026-08-14 快照）

已完成：

- 六台节点 frps 统一到 v0.70.1；十台机器（含家里四台）启用 BBR + fq
- 三台 ECS 开启 QUIC（`quicBindPort = 34566`），安全组已放行，实测三条 QUIC 链路均可登录
- 六条 `mcwok.cn` 子域名与裸域名的 A 记录已在阿里云 DNS 建好（SRV 决定不配，理由见第三节）
- Minecraft 主机（192.168.10.200）的 AccessHub 升到 0.3.0，七条线路入口
  25601-25607 全部绑定，探针监听 127.0.0.1:25610
- 六个 `frpc-multi@<线路>` 实例已启用，**六条 frp 线路实测均可进服**
- `GET /api/v1/net/nodes` 已可用，七条线路均返回
- **六个节点的 wss 探针全部上线**，实测均返回 101 Switching Protocols。
  五个走标准 443；**shanghai 例外，走 8443** —— 那台的 80/443 属于 WB_APP 业务隧道，
  不能占用，详见下方「shanghai 的特殊处理」
- **玩家自查页面已上线**：https://panel.mcwok.cn/network （公开路由，无需登录）
- **线路归属统计实测有效**：曾观测到一名玩家经广州线进服，统计端点如实归到
  `guangzhou online=1`、`unattributed=0`，转发器到会话认领这一整条链路已被端到端验证
- **xiamen（家宽直连）的服务端侧已就绪**：光猫改桥接、公网 IP 落在 OpenWrt
  （175.44.1.151，`home.shinoyuki.cn` 由 DDNS 跟随）；Minecraft 主机上装好 nginx 1.28.3
  与 acme.sh 3.1.5，`home.shinoyuki.cn` 证书已用 DNS-01 签发（**续期全自动**，
  ARI 窗口 2026-10-14），8443 探针 vhost 已起，本机 wss 握手返回 101

当时未完成：

- **xiamen 差 OpenWrt 的两条端口转发**，见「xiamen 的特殊处理」。在此之前该线在自查页上
  必然测失败
- 武汉、广州的安全组仍放行着 TCP 25610，建议收掉（探针不应直接暴露公网）

### shanghai 的特殊处理与证书续期风险

这台的 80/443 被 frps 的 tcp proxy 占着（`WB_APP_http` / `WB_APP_https`，转发家里某台机器上
的 nginx），是独立业务，不能动。由此带来两处偏离：

1. **探针挂在 8443**，`probe-url` 是 `wss://shanghai.mcwok.cn:8443/probe`，安全组需单独放行
   TCP 8443。本机 nginx 的 Ubuntu 默认站点必须禁用（`rm /etc/nginx/sites-enabled/default`），
   否则它要监听 80 会和 frps 抢端口导致 nginx 起不来。
2. **证书是手工 DNS-01 签的**（2026-08-14，有效期至 11-12，acme.sh 按 ARI 定在 **10-12 续期**）。

> **10-12 那次自动续期一定会失败。** `mcwok.cn` 的解析在另一个阿里云账号下，本机的 acme.sh
> 没有能写它 TXT 记录的凭据。届时要么再手工加一次 `_acme-challenge.shanghai` 的 TXT，
> 要么提前拿到持有该域名那个账号的 AccessKey 配上 `dns_ali` 让续期全自动。
> 不处理的话上海线的测速会在 11-12 证书过期后静默失效。

其余五个节点走 HTTP-01，`certbot.timer` 自动续期，无此问题。

### xiamen（家宽直连）的特殊处理

这条线不经 frp，是玩家的包直接打到家里的公网 IP。三处偏离标准做法，每一处都是被外部
条件逼的，不是随手选的：

1. **探针挂 8443，不是 443。** 厦门联通对家宽入站封了 80/443/8080/53，高位端口放行。
   443 收不到包，`probe-url` 必须显式带 `:8443`。碰巧和上海同端口，但原因完全不同 ——
   上海是 443 被 WB_APP 业务占着。

2. **域名是 `home.shinoyuki.cn`，不在 `mcwok.cn` 下。** 这是证书能否自动续期的分水岭：
   `mcwok.cn` 的解析在另一个阿里云账号下，手上的 AccessKey 写不了它的 TXT 记录
   （返回 `IncorrectDomainUser`），而这条线 80 被封、HTTP-01 也走不通 —— 两条自动化路径
   同时堵死，只剩手工加 TXT。`shinoyuki.cn` 在自己账号下，DNS-01 全自动，还顺带让 DDNS
   和证书共用同一条记录：公网 IP 一变，两边一起跟随。**上海就是反面教材。**

3. **OpenWrt 的内部端口是 25607，不是 25565。** 这条最容易写错，且错了不会报错：

   | 协议 | 外部端口 | 内部地址 | 内部端口 |
   | --- | --- | --- | --- |
   | TCP | 25565 | 192.168.10.200 | **25607** |
   | TCP | 8443 | 192.168.10.200 | 8443 |

   映射到 25565 玩家照样能进服，但绕过了转发器，线路归属全部落进 `unattributed`，
   这条线在自查页上等于白装。25607 才是转发器的 xiamen 入口。

   **25610 绝对不能映射** —— 探针只监听回环，映射出去等于把无鉴权回显服务挂上公网。

直连没有 PROXY protocol 头，转发器对此是原生支持的：它对每条新连接先探测 PROXY 头，
判定对端没发头时直接取 TCP 层的对端地址，而 OpenWrt 的 DNAT 保留源 IP，所以拿到的就是
玩家真实地址。线路归属靠"进的哪个入口端口"判定，与有没有 PROXY 头无关。

排障时注意区分两种失败：**RST（Connection refused）说明包到了 OpenWrt 但没有匹配的 DNAT
规则；超时（静默丢包）才是运营商封端口。** 2026-08-14 从杭州节点实测这两个端口都是 RST，
即链路本身通畅，只差规则。

### 改配置与升级时的两个要点

改 `probe-url`、展示名一类的纯配置项**不需要重启服务器**：改完 `common.toml` 执行
`accesshub reload` 即可，该命令可经 RCON 发送（端口见 server.properties）。
只有转发器的端口绑定是启动时完成的，增删线路或改 `listen-port` 才需要重启。

升级已有服务器时必踩的坑：`[network]` 配置段只在 `common.toml` **不存在**时才写入默认值，
所以给跑了很久的服务器换上带多线路功能的 jar，`network.*` 那些键根本不会出现在配置文件里，
读取时一律回退到 `enabled = false`，转发器会静默地不启动。必须手工把 `[network]` 段
补进 `common.toml`。

---

## 零、接入前的现网勘察（2026-08-14）

**本目录的 `frps-gz.toml` / `frps-sz.toml` 是按"全新部署"写的，直接覆盖现网会打断正在服务的隧道。**
下面是多线路接入动工前登录六台机器实测到的状态，它解释了为什么这两份文件只能当字段参考。

| 机器 | bindPort | 配置文件路径 | 当时已有的 proxy | 备注 |
|------|----------|--------------|------------------|------|
| 阿里云ECS-杭州1 47.118.28.70 | 7000 | `/usr/local/frp/frps.toml` | `mc_java_main`、`mc_extra_24444` | |
| 阿里云轻量-武汉1 47.122.120.140 | 7000 | `/etc/frp/frps.toml` | `win-ssh-7777`（离线） | |
| 阿里云轻量-广州1 8.148.217.146 | **48124** | `/etc/frp/frps.toml` | `win25h2-rdp`（在线） | `allowPorts` 白名单 |
| 阿里云轻量-杭州1 47.114.79.114 | 7000 | `/etc/frp/frps.toml` | 无 | 当时服务 inactive |
| 阿里云ECS-上海1 101.133.234.218 | 7000 | `/etc/frp/frps.toml` | 无 | 80/443 属 WB_APP 业务 |
| 阿里云ECS-深圳1 120.24.184.115 | 7000 | `/opt/frp/frps.toml` | 25565、22222 | 承载线上玩家与面板 API |

动任何一台节点的 frps 之前要记住的几件事：

1. **深圳的 frps 同时承载 Minecraft 25565 与 `api.mcwok.cn` 的后端 22222**（mod 的 HTTP API 经此暴露）。
   重配或重启该 frps 会同时打断线上玩家与面板 API。
2. **三台机器的 frps 配置路径各不相同**（`/etc/frp/`、`/opt/frp/`、`/usr/local/frp/`）。
   按本目录假设的 `/etc/frp/frps.toml` 部署，在另两台上会新建一份互相冲突的配置。路径一律从
   `systemctl cat frps.service` 的 ExecStart `-c` 参数取，见第一节。
3. **广州 frps 的 bindPort 是 48124 而非 7000**，且启用了 `allowPorts` 白名单（当时只开了
   24507-24509、24600）。frpc 申请 25565 / 25610 之前必须先在 frps 侧放行。
4. **广州 frps 上跑着在线的 `win25h2-rdp`**，整份覆盖配置会打断这条远程桌面隧道。

因此 `frps-gz.toml` / `frps-sz.toml` **不要整份覆盖**，应作为字段参考，
把 `allowPorts` 等必要项增量合并进现有配置。

同一次勘察得到的其它结论：

- **`mcwok.cn` 的 DNS 托管在阿里云 DNS**，不在 DNSPod。实测 NS 记录是 `ns1.alidns.com` /
  `ns2.alidns.com`。本目录里所有以 DNSPod 为前提的文件
  （`issue-wildcard-cert.sh`、`ddns-dnspod.sh`、`Caddyfile-home` 的 ACME 段）
  都基于过时信息，**不要照用**。
- **证书不需要泛域名，云节点也就不需要任何 DNS API 凭据。**
  每台节点只需自己那一个子域名的证书，用 `issue-node-cert.sh` 走 certbot HTTP-01 即可。
  各机本来就在用同样方式签着 `api.mcwok.cn`、`panel.mcwok.cn`、`wiki.mcwok.cn`、
  `questionnaire.mcwok.cn` 等证书，路径已验证可行。只有 80 端口不可用的两条线
  （shanghai、xiamen）才走 DNS-01。
- **深圳这台是阿里云 ECS，不是腾讯云。** `frps-sz.toml` 文件头里的"腾讯云深圳"是早先的错误标注。
- **十台机器（六台云节点 + 家里四台）已统一启用 BBR + fq**。配置写在
  `/etc/sysctl.d/99-bbr.conf`，删掉这一个文件即可回退；`/etc/modules-load.d/bbr.conf`
  保证开机加载 `tcp_bbr`。当时只有杭州1、上海、武汉三台已是 bbr，其余七台是 cubic。
  两个易踩的点：一是 `tcp_available_congestion_control` 只列出**已加载**的模块，
  没 `modprobe tcp_bbr` 就去查会把「模块没加载」误判成「内核不支持」；二是
  `net.core.default_qdisc` 只影响此后新建的 qdisc，已在跑的网卡要 `tc qdisc replace`
  换一次才立即生效，且只该动物理口 —— 网桥、虚拟机 tap、容器 veth 上换 qdisc 既无收益
  又可能扰动客户机网络。
- **广州机到 Cloudflare 当时是通的**（实测 TLS 握手成功）。更早"广州出境不通、需经深圳中转"的
  结论是 2026-07-26 的观测。这类连通性会随时间反复，用前复测。

---

## 一、拓扑

七条线路彼此独立，任意一条挂掉不影响其余。玩家连的是哪条，就从对应的入口端口进入转发器：

```
玩家
 |
 +-- 六条 frp 中转线 --> 节点 frps :25565 --隧道--> 家里 frpc --+
 |                                                              |
 +-- 一条家宽直连线 --> 路由器端口映射 ------------------------+
                                                                |
                                                                v
                                                      各线路独占的入口端口
                                                                |
                                            [Forge mod 内置 TCP 前置转发器]
                                            按入口端口区分线路归属
                                            解析 PROXY protocol 取玩家真实 IP
                                                                |
                                                                v
                                                Minecraft 服务端 127.0.0.1:25565
```

线路参数总表（2026-08-14 逐台实测校准）：

线路 id 用地区拼音，同地区两个节点则加序号，ECS 排在轻量之前。入口端口顺序与优先级
一致（ECS 在前、轻量居中、家宽殿后），排障时看端口号就知道玩家走的是哪一档。

| 线路 id | 展示名 | 节点 | 公网 IP | 档次 | frps bindPort | 入口端口 | frpc 管理端口 | PROXY v2 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `hangzhou1` | 杭州一线 | 阿里云ECS-杭州1 | 47.118.28.70 | ECS | 7000 / QUIC 34566 | 25601 | 7401 | 开 |
| `shanghai` | 上海线 | 阿里云ECS-上海1 | 101.133.234.218 | ECS | 7000 / QUIC 34566 | 25602 | 7402 | 开 |
| `shenzhen` | 深圳线 | 阿里云ECS-深圳1 | 120.24.184.115 | ECS | 7000 / QUIC 34566 | 25603 | 7403 | 开 |
| `hangzhou2` | 杭州二线 | 阿里云轻量-杭州1 | 47.114.79.114 | 轻量 | 7000 | 25604 | 7404 | 开 |
| `wuhan` | 武汉线 | 阿里云轻量-武汉1 | 47.122.120.140 | 轻量 | 7000 | 25605 | 7405 | 开 |
| `guangzhou` | 广州线 | 阿里云轻量-广州1 | 8.148.217.146 | 轻量 | **48124** | 25606 | 7406 | 开 |
| `xiamen` | 厦门联通专线 | 家庭宽带（厦门联通 AS4837） | 175.44.1.151（动态，由 `home.shinoyuki.cn` 跟随） | 家宽 | 不经 frp | 25607 | 无 | **关** |

**广州的 frps bindPort 是 48124 而不是 7000**，是全场唯一的例外，配 frpc 时最容易踩。

`xiamen` 关闭 PROXY protocol 的原因：直连线路玩家的 TCP 源地址本身就是真实地址，
多加一层头反而要额外剥。其余六条经隧道后源地址已被改写成回环，不带头就丢失真实 IP。
转发器不需要为此配任何开关，它对每条连接自动探测有没有 PROXY 头。

各节点的 `auth.token` **互不相同**（上海与杭州二线恰好共用一个），必须逐台去各自的
frps 配置里取。配置文件路径三种都有，一律从 `systemctl cat frps.service` 的
ExecStart `-c` 参数取，不要凭猜：

```bash
systemctl cat frps.service | grep -m1 '^ExecStart'
```

### 传输协议：三台 ECS 走 QUIC，轻量走 TCP

按"ECS 优先"的思路，三台 ECS 节点都配上了 `quicBindPort = 34566`（同一个端口号，
不同机器不冲突，一个数字比三个好记）。三台轻量保持 TCP。

**服务端在监听不等于外面进得来。** 阿里云安全组对 UDP 和 TCP 是分开放行的，
开了 TCP 7000 不等于开了 UDP 34566。2026-08-14 首次从 Minecraft 主机实测时只有深圳能通
（它此前配 QUIC 时已放行过），杭州1 与上海都是超时；在安全组里补上入方向规则
（协议 UDP、端口 34566）之后三条 QUIC 链路才全部可登录。

**安全组没放行 UDP 的节点，frpc 必须用 TCP**，配成 quic 只会一直连不上。

验证手法（服务端监听是必要条件而非充分条件，一定要拿真实 frpc 验）：在 Minecraft
主机上写一份**不含任何 proxy**的临时 frpc 配置，`transport.protocol = "quic"` 加
`loginFailExit = true`，跑一次看是否输出 `login to server success`。零 proxy 的 frpc
只做控制面登录，不会在服务端注册任何东西，测完即退。

### 传输协议：frp 不会自动回退

**frp 没有协议自动回退机制。** 官方文档只描述了 `transport.protocol` 的静态选择，
不存在「QUIC 连不上就降级 TCP」的行为。配成 quic 就会一直重试 quic，UDP 一旦被
运营商 QoS 掐掉或被中间设备丢弃，这条线路会**持续不可用且不会自愈**。

这正是三台 ECS 走 QUIC、三台轻量走 TCP 的意义所在：协议冗余落在**线路之间**而不是
线路内部。UDP 若在某段网络被整体限制，三条 TCP 线照常工作；反过来若运营商对长连接
TCP 做针对性干扰，三条 QUIC 线还在。玩家换条线即可，比在单条线路内部实现协议切换
简单得多也可靠得多。

真要做自动回退，只能在 frp 外面实现：定时查 frpc 管理接口的
`http://127.0.0.1:<webServer.port>/api/status`，发现该 proxy 持续 offline 超过阈值
就改写配置里的 `transport.protocol` 并重启该实例。属于额外的运维组件，本方案暂不引入。

### 延迟探针的通路

延迟探针是另一条独立通路。六条 frp 线在各自节点终结 TLS；`xiamen` 不经过任何云节点，
TLS 必须在家里那台机器上终结：

```
frp 中转线:
浏览器 --wss://<线路 id>.mcwok.cn/probe--> 节点 nginx:443（shanghai 为 8443）
       --http/1.1--> 节点 127.0.0.1:25610（frps 暴露，防火墙锁本机）
       --frp 隧道--> 家里 :25610（mod 内置 WebSocket 探针）

xiamen 直连线（不经过云节点）:
浏览器 --wss://home.shinoyuki.cn:8443/probe--> OpenWrt DNAT
       --> 192.168.10.200:8443 nginx
       --http/1.1--> 127.0.0.1:25610（mod 内置 WebSocket 探针）
```

所有线路的探针都指向家里同一个 25610：探针服务只有一个实例，各线路的差异体现在
流量走的隧道不同，而不是后端不同。

探针必须和 Minecraft 在同一台机器上，并经由与游戏流量完全相同的隧道暴露。若把探针放在
frps 一侧，测到的只是"玩家到中转节点"的半程。

探针是标准 WebSocket（RFC 6455 升级握手 + 文本帧回显），所以反代配置必须
正确透传 Upgrade / Connection 头。

### 为什么是六个 frpc 实例

**一个 frpc 进程只能连接一个 frps。** frpc 配置里的 `serverAddr` / `serverPort`
是全局字段而非每个 proxy 的字段，没有任何写法能让单个进程同时挂上多台 frps。
所以家里必须为每条 frp 线路跑一个互相独立的 frpc 进程，各吃一份从
`frpc-template.toml` 派生的配置。`xiamen` 是直连线，不需要 frpc。

六个实例的 `webServer.port` 必须两两错开（7401-7406，见上方参数表），否则同机
第二个进程会因端口占用直接启动失败。

现网的六个实例以 `frpc-multi@<线路 id>` 命名（如 `frpc-multi@guangzhou`）。仓库里的
`frpc@.service` 是 template unit 的参考写法，按它部署则实例名是 `frpc@<线路 id>`、
配置文件是 `/etc/frp/frpc-<线路 id>.toml`。下文命令按仓库里的写法给出，在现网操作时
把单元名换成实际的那个。Windows 主机的做法见第九节。

### frps 版本

**六台已于 2026-08-14 全部统一到 v0.70.1**，升级前各版本是 0.58.1 到 0.70.1 不等。
家里的 frpc 用同版本的 v0.70.1 即可，不再有跨版本兼容问题。

升级手法（以后再升可沿用）：节点从 GitHub 拉取很慢，改为在本地下载一次
`frp_0.70.1_linux_amd64.tar.gz` 后经 SFTP 分发。顺序是先上传新二进制到临时路径、
用**新二进制**对**现有配置**跑一次 `frps verify`，通过了才替换并重启，失败则原地放弃，
重启后若服务未能起来自动回滚。旧二进制保留为 `<原路径>.bak-<旧版本>`。

一个容易误判的现象：frps 重启后 `ss -tln` 会短暂看不到各 proxy 的端口
（25565、22222 等）。那些端口由 frpc 注册，要等客户端重连才会重新监听，
不是升级失败。等几秒再看，或直接查 `journalctl -u frps`。

---

## 二、文件清单

| 文件 | 部署位置 | 状态与作用 |
| --- | --- | --- |
| `frpc-template.toml` | 家里，每条 frp 线路派生一份 `/etc/frp/frpc-<线路 id>.toml` | **在用**。frpc 配置模板，按第一节参数表替换占位符 |
| `frpc@.service` | 家里 `/etc/systemd/system/` | frpc 的 template unit，每条 frp 线路一个实例 |
| `frps.service` | 节点 `/etc/systemd/system/` | frps 开机自启，全新节点才需要 |
| `nginx-probe.conf` | 各 frp 节点 `/etc/nginx/conf.d/probe-<线路 id>.conf` | **在用**。节点侧 wss 探针反代，2026-08-14 实际部署并验证过的版本 |
| `issue-node-cert.sh` | 各 frp 节点，手工执行 | 用 certbot HTTP-01 签本节点子域名证书，**不需要任何 DNS API 凭据** |
| `nginx-probe-home.conf` | Minecraft 主机 `/etc/nginx/conf.d/probe-home.conf` | **在用**。xiamen 线的 wss 探针反代（8443，`home.shinoyuki.cn`） |
| `frps-gz.toml`、`frps-sz.toml` | **仅作字段参考，勿直接覆盖** | frps 字段写法示例，理由见第零节 |
| `Caddyfile-home` | **未采用** | 家宽侧探针反代的 Caddy 方案，按 `home.mcwok.cn` + 443 + DNSPod 编写，与现网（`home.shinoyuki.cn` + 8443 + 阿里云 DNS）不符 |
| `issue-wildcard-cert.sh` | **已作废，勿用** | 基于 DNSPod 的泛域名签发；DNS 在阿里云，且不再需要泛域名 |
| `ddns-dnspod.sh` | **未采用** | 调的是 DNSPod API，且目标是 `home.mcwok.cn`；现网的 DDNS 由 OpenWrt 维护 `home.shinoyuki.cn` |

`frpc@.service`、`frps.service` 等文件头部的注释仍以最初的 gz / sz 双线路为例，
线路名以本文档第一节的参数表为准。

---

## 三、DNS 解析

`mcwok.cn` 的 DNS 托管在**阿里云 DNS**（NS 为 `ns1.alidns.com` / `ns2.alidns.com`）。
解析记录去阿里云控制台加，需要 API 时用的也是阿里云 AccessKey，DNSPod 的密钥在这里没有任何作用。
注意该域名的解析与 `shinoyuki.cn` **不在同一个阿里云账号**下。

### A 记录

| 主机记录 | 类型 | 记录值 | TTL | 说明 |
| --- | --- | --- | --- | --- |
| `hangzhou1` | A | 47.118.28.70 | 600 | 杭州一线（ECS） |
| `shanghai` | A | 101.133.234.218 | 600 | 上海线（ECS） |
| `shenzhen` | A | 120.24.184.115 | 600 | 深圳线（ECS） |
| `hangzhou2` | A | 47.114.79.114 | 600 | 杭州二线（轻量） |
| `wuhan` | A | 47.122.120.140 | 600 | 武汉线（轻量） |
| `guangzhou` | A | 8.148.217.146 | 600 | 广州线（轻量） |
| `@` | A | 120.24.184.115 | 600 | 裸域名兜底，指向深圳 |

**`xiamen` 在 `mcwok.cn` 下没有记录**，它用的是 `shinoyuki.cn` 域下的 `home.shinoyuki.cn`
（由 OpenWrt 上的 DDNS 维护）。理由不是命名偏好，是证书能否自动续期，详见
「xiamen 的特殊处理」。这条记录的 TTL 应填套餐允许的最小值（阿里云 DNS 免费版最低 600 秒，
付费版可降到 60 秒）：TTL 越小，家宽换 IP 后玩家恢复得越快。

七条线路都对外监听标准端口 25565，玩家只填域名、不带端口即可进服。

新增线路时**先建解析再签证书**：HTTP-01 校验按域名回连，解析没指到本机时请求会打到别的机器上，
签发失败还会消耗配额。

关于 `@`：裸域名指向深圳只是一个默认选择，玩家输 `mcwok.cn` 会走深圳线，
想换成别的节点改这一条记录即可。各线路本身互不依赖这条记录。

探针不需要额外的解析记录 —— `wss://<线路域名>/probe` 复用同一条 A 记录，
由该节点的 nginx 按 `server_name` 分流。

`_acme-challenge.*` 的 TXT 记录**不要手工常驻创建**：走 HTTP-01 的五个节点不产生此记录；
`home.shinoyuki.cn` 由 acme.sh 的 `dns_ali` 自动写入与清理，手工建了同名记录会与之冲突。
唯一需要手工加 TXT 的是 shanghai（见上方续期风险）。

### 为什么不配 SRV 记录

曾经规划过用 `_minecraft._tcp.mcwok.cn` 的 SRV 记录给裸域名做多线路分流（ECS 优先级 1、
轻量优先级 2），最终决定不配：

1. **SRV 不是故障转移，也不是可靠的负载均衡。** Minecraft 客户端解析 SRV 后只会拿到一个目标去连，
   连不上就是连不上，不会自动重试下一条；同优先级多条记录的权重分配在各客户端实现里也不一致，
   很多实现直接取第一条。
2. 每条线路都监听标准端口 25565，玩家直接输 `guangzhou.mcwok.cn` 这样的子域名即可，
   本来就不需要 SRV 来隐藏端口。
3. SRV 在部分第三方启动器和旧版客户端上解析行为不一致，而 A 记录是所有版本都稳定支持的。

真正的选路靠玩家自己按自查页面的实测结果挑子域名。家宽线更不适合进 SRV：它是动态 IP，
DDNS 同步存在窗口期，一旦解析滞后就会把裸域名的玩家整批送进黑洞。

---

## 四、端口规划

### 节点机（六台 frp 节点）

| 端口 | 协议 | 用途 | 对公网开放 |
| --- | --- | --- | --- |
| 7000（广州为 48124） | TCP | frps 接受 frpc 接入（`bindPort`） | 是 |
| 34566 | UDP | frps 的 QUIC 入口（仅三台 ECS） | 是，安全组需**单独放行 UDP** |
| 25565 | TCP | Minecraft 入口（frps `remotePort`） | 是 |
| 25610 | TCP | 探针（frps `remotePort`） | **否**，必须用防火墙锁到本机 |
| 80 | TCP | certbot HTTP-01 签发与续期 | 是（shanghai 除外，其 80 属 WB_APP） |
| 443（shanghai 为 8443） | TCP | nginx，`/probe` 的 wss 入口 | 是 |
| 7500 | TCP | frps dashboard（若启用） | **否**，`webServer.addr` 须绑 127.0.0.1 |

**25610 是本方案最容易出错的地方**：frps 把 `remotePort` 绑在 `bindAddr`
（0.0.0.0）上，所以 25610 默认裸奔在公网，任何人都能绕开 nginx 直连
`<节点IP>:25610` 打探针。

不能靠 frps 的 `proxyBindAddr = "127.0.0.1"` 解决 —— 那个设置对**所有** proxy
一起生效，会把 25565 也绑到回环上，玩家直接进不来。唯一正确的做法是防火墙
（云厂商安全组不放行 25610，主机防火墙只允许本机访问）。

### 家里的 Minecraft 主机（192.168.10.200）

| 端口 | 协议 | 用途 | 经路由器映射到公网 |
| --- | --- | --- | --- |
| 25565 | TCP | Minecraft 服务端本体 | 否，仅本机/内网 |
| 25601-25606 | TCP | 转发器的六个 frp 线路入口，顺序见第一节参数表 | 否，仅本机 frpc 访问 |
| 25607 | TCP | 转发器的 xiamen 线路入口 | **是**，公网 25565 -> 192.168.10.200:**25607** |
| 25610 | TCP | mod 内置 WebSocket 探针，只监听回环 | 否，**绝对不能映射** |
| 8443 | TCP | nginx，xiamen 线 `/probe` 的 wss 入口 | **是**，公网 8443 -> 192.168.10.200:8443 |
| 7401-7406 | TCP | 六个 frpc 实例的管理接口，只监听回环 | 否 |

家宽这条线**也走转发器**（进 25607 而不是直连 25565），目的是让七条线路的
人数统计口径完全一致。

`frpc-template.toml` 里 `localIP = "127.0.0.1"` 的前提是 **frpc 与 Minecraft 服务端跑在
同一台机器上**。如果把 frpc 挪到家里另一台机器，两处都要改：配置里改成 Minecraft 主机的
内网地址，同时 mod 的 `network.bind-host` 要能被那台机器访问到。

---

## 五、token 与占位符

frps 与 frpc 的 `auth.token` 必须逐字符一致。用足够长的随机串，不要用可猜的口令：

```bash
# Linux / macOS
openssl rand -base64 32

# Windows PowerShell
[Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Max 256 }))
```

各节点应使用**不同的 token**，这样单个节点被拿下不会连带其它节点。
`allowPorts` 白名单是第二道防线：即使 token 泄露，攻击者也只能申请白名单内的端口，
无法把节点当成任意端口的跳板机。

给已在运行的节点接线时不要新生成 token，去该节点现有的 frps 配置里取。

| 占位符 | 出现在 | 说明 |
| --- | --- | --- |
| `<NODE_ID>` | `frpc-template.toml` | 线路 id，须与 mod 配置 `network.nodes` 里的 `id` 一致 |
| `<NODE_DISPLAY>` | `frpc-template.toml` | 展示名，仅用于注释 |
| `<SERVER_ADDR>` | `frpc-template.toml` | 节点公网 IP。刻意用 IP 而非域名：隧道不该被 DNS 故障连带下线 |
| `<SERVER_PORT>` | `frpc-template.toml` | 该节点 frps 的 `bindPort`，广州是 48124 |
| `<FRP_TOKEN>` | `frpc-template.toml`、`frps-*.toml` | 同一节点的 frps / frpc 必须相同 |
| `<RELAY_PORT>` | `frpc-template.toml` | 该线路在转发器上的入口端口（25601 起），须与 mod 配置的 `listen-port` 一致 |
| `<ADMIN_PORT>` | `frpc-template.toml` | 该 frpc 实例的管理端口（7401 起），同机各实例互不相同 |
| `<FRP_DASHBOARD_PASSWORD>` | `frps-*.toml` | frps dashboard 密码 |
| `<DOMAIN>` | `nginx-probe.conf` | 本节点的线路域名，如 `hangzhou1.mcwok.cn` |
| `<SLUG>` | `nginx-probe.conf` | 线路 id，用于区分 `ssl_session_cache` 的共享区名 |

`<DNSPOD_API_ID>`、`<DNSPOD_API_TOKEN>`、`<YOUR_EMAIL>` 只出现在未采用或已作废的三个文件里，无需处理。

---

## 六、接入步骤

### 新增一条 frp 线路

**节点侧**

- [ ] 在阿里云 DNS 为该线路建 A 记录，并用 `dig +short` 确认已生效
- [ ] frps：已有 frps 的节点**不要覆盖配置**，把 25565 与 25610 增量加进 `allowPorts`（若启用了白名单），
      改完先 `frps verify -c <配置路径>` 再重启。全新节点才按下面的"全新节点安装 frps"来
- [ ] 安全组与主机防火墙：放行 frps `bindPort`、25565、80、443；走 QUIC 的再放行 UDP 34566；
      **确认 25610 与 7500 未对公网放行**
- [ ] 签证书：`CERT_EMAIL=<邮箱> ./issue-node-cert.sh <线路域名>`
- [ ] `nginx-probe.conf` -> `/etc/nginx/conf.d/probe-<线路 id>.conf`，替换 `<DOMAIN>` 与 `<SLUG>`，
      `nginx -t` 通过后 reload

节点上通常已经跑着别的站点（深圳有面板、广州有问卷）。`nginx-probe.conf` 是一个独立的
`server` 块，放进 `conf.d` 后由 SNI 按 `server_name` 分流，与既有 vhost 共存。
部署时踩过的两个坑已写进该文件的注释：`http2 on;` 是 nginx 1.25.1 起的语法，而这些机器是
1.24，会拒绝整份配置；map 变量名不带命名空间会与节点上已有的站点撞车导致 nginx 拒绝启动
（文件里用的是 `$mcwok_probe_upgrade`）。签证书不要用 `certbot --standalone`，
它独占 80 会打断节点上跑着的其它站点；`issue-node-cert.sh` 在检测到 nginx 运行时会自动改用 nginx 插件。

**家里（Minecraft 主机）**

- [ ] `frpc-template.toml` -> `/etc/frp/frpc-<线路 id>.toml`，按第一节参数表替换全部占位符
- [ ] `frpc verify -c /etc/frp/frpc-<线路 id>.toml`
- [ ] `systemctl enable --now frpc@<线路 id>`
- [ ] 在 `common.toml` 增加一个 `[[network.nodes]]` 块，`listen-port` 与 frpc 的 `localPort` 一致
- [ ] **重启 Minecraft 服务端**（入口端口在启动时绑定；仅改展示名或 `probe-url` 时 `accesshub reload` 即可）
- [ ] 走完第七节的验证清单

**全新节点安装 frps**

- [ ] 装 frp v0.70.1，二进制放 `/usr/local/bin/frps`
- [ ] `useradd --system --no-create-home --shell /usr/sbin/nologin frp`
- [ ] `mkdir -p /var/log/frp && chown frp:frp /var/log/frp`
- [ ] 参照 `frps-gz.toml` 写 `/etc/frp/frps.toml`，替换占位符
- [ ] `frps.service` -> `/etc/systemd/system/`，`systemctl enable --now frps`

### 家宽直连线路

这条线涉及路由器改动和动态 IP，故障因素最多，应在 frp 线路稳定之后再接，
出问题时才有稳定的对照组。现网 xiamen 线的做法：

- [ ] 确认家宽拿到的是独立公网 IP（非 CGNAT），公网 IP 落在路由器上
- [ ] 路由器配置 DDNS，维护自己账号下的域名记录（现网为 `home.shinoyuki.cn`）
- [ ] 在 Minecraft 主机上用 acme.sh 走 DNS-01 签证书，命令见 `nginx-probe-home.conf` 文件头
- [ ] `nginx-probe-home.conf` -> `/etc/nginx/conf.d/probe-home.conf`，`nginx -t` 后 reload
- [ ] 路由器端口映射两条，见第八节。**内部端口是转发器入口而不是 25565**
- [ ] 核对第八节的"绝对不能映射"清单，确认没有多开
- [ ] `common.toml` 里该线路的 `endpoint` 与 `probe-url` 填家宽域名，`probe-url` 带上实际端口

---

## 七、验证清单

### 通用原则：不要用 ping 判断连通性

**这套环境 ICMP 不通是正常的。** 云厂商安全组默认不放行 ICMP，家宽也常被过滤。
`ping` 不通完全不代表端口不通，反过来 `ping` 通也不代表 frps 在监听。
一律用 **TCP 层**的探测手段：

```powershell
# Windows PowerShell
Test-NetConnection -ComputerName guangzhou.mcwok.cn -Port 25565
# 只看 TcpTestSucceeded 那一行，True 即通。PingSucceeded 是 False 属于正常。
```

```bash
# Linux / macOS
nc -vz guangzhou.mcwok.cn 25565
# 或
timeout 5 bash -c '</dev/tcp/guangzhou.mcwok.cn/25565' && echo PASS || echo FAIL
```

下面以 `guangzhou` 为例，其余线路把域名与线路 id 换掉即可。

### 1. DNS 解析

```bash
dig +short guangzhou.mcwok.cn      # 期望 8.148.217.146
dig +short home.shinoyuki.cn       # 期望家宽当前公网 IP
```

判定：解析值与第三节的表格一致即 PASS。刚改完记录没生效的话按 TTL 等待，
或用 `dig @ns1.alidns.com guangzhou.mcwok.cn` 直接问权威 DNS 绕过本地缓存。

### 2. frps 是否起来了（在节点机上）

```bash
systemctl status frps
ss -lntp | grep -E '7000|48124|7500|25565|25610'
```

判定：

- `bindPort` 应当监听在 `0.0.0.0` -> PASS
- 7500 若存在，必须监听在 `127.0.0.1`；显示 `0.0.0.0:7500` 说明
  `webServer.addr` 没生效 -> **FAIL，立即修**
- 25565 / 25610 只有在对应 frpc 连上来之后才会出现，frpc 没起时看不到是正常的

配置本身是否合法可以在启动前先验：

```bash
frps verify -c <frps 配置路径>
frpc verify -c /etc/frp/frpc-guangzhou.toml
```

### 3. frpc 是否挂上了（在 Minecraft 主机上）

```bash
systemctl status frpc@guangzhou
frpc status -c /etc/frp/frpc-guangzhou.toml
```

判定：`mc-guangzhou` 和 `probe-guangzhou` 两个 proxy 的状态都应当是 `running`。
出现 `start error` 通常是这几种：

| 现象 | 成因 |
| --- | --- |
| `port not allowed` | `remotePort` 超出了 frps 的 `allowPorts` 白名单 |
| `proxy name conflict` | 同名 proxy 已存在，多半是旧进程没退干净 |
| `authorization failed` | token 与 frps 侧不一致 |
| 本地端口 connect refused | 转发器/探针没监听，先查 mod 是否加载、`network.enabled` 是否为 `true` |

### 4. 端口连通性（从任意外网机器）

```powershell
Test-NetConnection -ComputerName guangzhou.mcwok.cn -Port 25565   # 期望 True
Test-NetConnection -ComputerName home.shinoyuki.cn  -Port 25565   # 期望 True
Test-NetConnection -ComputerName guangzhou.mcwok.cn -Port 25610   # 期望 False
Test-NetConnection -ComputerName guangzhou.mcwok.cn -Port 7500    # 期望 False
Test-NetConnection -ComputerName home.shinoyuki.cn  -Port 25610   # 期望 False
```

**25610 和 7500 必须探测失败。** 探测成功说明防火墙没配对，探针或 dashboard
正裸奔在公网上 -> FAIL，回到第四节处理。

### 5. TLS 证书

```bash
openssl s_client -connect guangzhou.mcwok.cn:443 -servername guangzhou.mcwok.cn </dev/null 2>/dev/null \
    | openssl x509 -noout -subject -dates -ext subjectAltName
```

判定：证书的名字应当是该线路自己的域名（每个节点各签各的，不是泛域名），
且 `notAfter` 是将来的日期。shanghai 与 xiamen 把端口换成 8443。

### 6. WebSocket 探针

```bash
curl -i --http1.1 \
     -H "Connection: Upgrade" \
     -H "Upgrade: websocket" \
     -H "Sec-WebSocket-Version: 13" \
     -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
     https://guangzhou.mcwok.cn/probe
```

shanghai 的地址是 `https://shanghai.mcwok.cn:8443/probe`，xiamen 是
`https://home.shinoyuki.cn:8443/probe`。

判定：

- 返回 `HTTP/1.1 101 Switching Protocols` -> PASS
- 返回 `200` 或 `404` -> 反代的 location 没匹配上，或反代没到探针
- 返回 `502` -> 后端不可达。frp 线路是 frps 的 25610 没监听（即 frpc 的
  `probe-<线路 id>` proxy 没起来）；xiamen 是本机探针没监听（查 mod 是否加载、
  `network.probe.enabled` 是否为 `true`）
- 长时间挂起无响应 -> frp 线路多半是探针那条 proxy 误开了 PROXY protocol，
  探针把二进制头当成 HTTP 请求行读了进去
- xiamen 连接被拒绝（RST）-> 路由器没有匹配的端口映射；超时 -> 运营商封了该端口

### 7. 玩家真实 IP 与线路归属（最终验收）

从外网用真实客户端分别连各线路域名进服，然后查 `GET /api/v1/net/nodes`：

- 该玩家应当计入对应线路的 `online`，`unattributed` 不应增加。
  某条 frp 线路人数恒为 0，检查 frpc 的 `localPort` 是否指向了该线路自己的入口端口。
  xiamen 线归属落进 `unattributed`，八成是路由器把公网 25565 映射到了内网 25565 而不是 25607。
- 服务端日志与白名单审计记录里的**玩家 IP 应当是客户端的真实公网 IP**。
  如果 frp 线路记录到的是 `127.0.0.1`，说明 PROXY protocol 链路断了，查
  `frpc-<线路 id>.toml` 里主线路 proxy 的 `transport.proxyProtocolVersion = "v2"` 是否存在。

---

## 八、家宽侧的 TLS 反代与路由器端口映射

xiamen 这条线不经过任何云节点，`wss://home.shinoyuki.cn:8443/probe` 的 TLS
**必须在家里那台机器上终结**。现网用的是 nginx + acme.sh：

- 配置文件 `nginx-probe-home.conf`，部署在 Minecraft 主机的 `/etc/nginx/conf.d/probe-home.conf`，
  监听 8443。
- 证书用 acme.sh 走 DNS-01（`dns_ali`）签发，需要 `shinoyuki.cn` 所属账号的 AccessKey；
  签发与安装命令见 `nginx-probe-home.conf` 文件头。DNS-01 不依赖任何入站端口，
  所以签发这一步在端口映射配好之前就能完成，续期也全自动。
- 为什么是 8443、为什么域名不在 `mcwok.cn` 下，见「xiamen 的特殊处理」。

`Caddyfile-home` 是早期设想的 Caddy 方案，没有采用，文件里的域名、端口与 DNS 服务商都与现网不符。

### 路由器端口映射

需要在路由器上映射的端口**只有两个**：

| 公网端口 | 映射到 | 用途 |
| --- | --- | --- |
| TCP 25565 | 192.168.10.200:**25607** | Minecraft 直连入口（转发器的 xiamen 入口） |
| TCP 8443 | 192.168.10.200:8443 | 探针 TLS 入口（nginx） |

25565 是**端口转换映射**：公网 25565 映射到内网的 **25607**，不是 25565。

### 绝对不能映射的端口

| 端口 | 为什么不能映射 |
| --- | --- |
| **25601-25606** | 六条 frp 线路的转发器本地入口，只允许本机 frpc 访问。暴露出去等于开了一个不受 frps 白名单和 token 保护的旁路，且外部直连进来的玩家会被错误地计到那条线路名下 |
| **25565**（内网真实端口） | Minecraft 服务端本体。一旦被直接暴露，玩家就绕过了转发器，**线路统计会漏人**，玩家真实 IP 记录也一并失效 |
| **25610** | 探针本体，无鉴权的明文 WebSocket 回显。对外只经 8443 上的 TLS 反代提供 |
| **7401-7406** | 六个 frpc 实例的管理接口，能读取配置和 token |

最容易犯的错是把公网 25565 直接映射到内网 25565 —— 服能进，一切看起来正常，
但这条线的人数统计恒为 0，这些玩家全部落进 `unattributed`。
排查时会因为"功能完全正常"而极难想到映射写错了。

### 家宽侧防火墙

frpc 是**主动出站**连接节点的，不需要任何入站规则。
需要放行入站的只有映射进来的 25607 和 8443 两个端口。

---

## 九、Windows 上把 frpc 注册成服务

现网的 Minecraft 主机是 Linux，用不到本节。只有把 frpc 放到一台 Windows 机器上运行时，
`frpc@.service` 才用不上，改用下面两种方式之一。无论哪种，都要记住**每条 frp 线路是一个独立进程**，
有几条线路就要注册几个服务。下面以 `guangzhou` 一条线路为例。

Windows 下配置文件里的 `log.to` 要改成 Windows 路径，或用相对路径让日志落在服务的工作目录下；
下面两种方式都显式指定了工作目录。

### 方式一：nssm（推荐）

nssm 能把任意 exe 包装成 Windows 服务，带自动重启和日志重定向，
比计划任务更适合常驻进程。从 https://nssm.cc 下载后：

```powershell
nssm install frpc-guangzhou "C:\frp\frpc.exe" "-c" "C:\frp\frpc-guangzhou.toml"
nssm set frpc-guangzhou AppDirectory "C:\frp"
nssm set frpc-guangzhou DisplayName "frp client (guangzhou 线路)"
nssm set frpc-guangzhou Start SERVICE_AUTO_START
# 进程退出后 10 秒重启，对应 systemd 的 RestartSec=10s
nssm set frpc-guangzhou AppRestartDelay 10000
nssm set frpc-guangzhou AppStdout "C:\frp\logs\frpc-guangzhou.out.log"
nssm set frpc-guangzhou AppStderr "C:\frp\logs\frpc-guangzhou.err.log"

Start-Service frpc-guangzhou
Get-Service frpc-guangzhou
```

其余线路重复一遍，服务名和配置文件都要换。卸载：`nssm remove frpc-guangzhou confirm`

### 方式二：Windows 计划任务

不想装第三方工具就用计划任务。注意计划任务**没有进程守护能力**，
frpc 崩了不会自动拉起，只能靠"启动时"触发器在重启后恢复。
所以优先选 nssm。

```powershell
$action  = New-ScheduledTaskAction -Execute "C:\frp\frpc.exe" `
                                   -Argument "-c C:\frp\frpc-guangzhou.toml" `
                                   -WorkingDirectory "C:\frp"
$trigger = New-ScheduledTaskTrigger -AtStartup
# 用 SYSTEM 账户运行，这样不登录也执行；RunLevel Highest 避免权限问题
$principal = New-ScheduledTaskPrincipal -UserId "SYSTEM" `
                                        -LogonType ServiceAccount `
                                        -RunLevel Highest
# 默认策略会在任务跑满 3 天后强杀，对常驻进程必须关掉
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries `
                                         -DontStopIfGoingOnBatteries `
                                         -ExecutionTimeLimit ([TimeSpan]::Zero) `
                                         -RestartCount 999 `
                                         -RestartInterval (New-TimeSpan -Minutes 1)

Register-ScheduledTask -TaskName "frpc-guangzhou" -Action $action -Trigger $trigger `
                       -Principal $principal -Settings $settings
```

`-ExecutionTimeLimit ([TimeSpan]::Zero)` 这一行不能省。计划任务默认最长执行
3 天就强制结束，对 frpc 这种要长期常驻的进程，表现是**跑了三天线路突然消失**，
排查起来极难定位。

### Windows 防火墙

frpc 是**主动出站**连接节点的，frp 线路不需要在 Windows 防火墙上开任何入站规则。
只有 Minecraft 服务端本身跑在这台 Windows 上、并且承载家宽直连线路时，
才需要放行第八节映射表里的两个内部端口。

---

## 十、常见故障速查

| 现象 | 优先怀疑 |
| --- | --- |
| 升级 jar 后转发器没启动、`relayEnabled` 为 `false` | `common.toml` 里没有 `[network]` 段。该段只在配置文件不存在时生成，升级需手工补 |
| frp 线路玩家 IP 全是 127.0.0.1 | frpc 主线路 proxy 漏了 `transport.proxyProtocolVersion = "v2"` |
| 某条 frp 线路人数统计恒为 0 | 入口端口串了，检查 frpc 的 `localPort` 是否等于该线路的 `listen-port` |
| xiamen 线玩家能进服但全部计入 `unattributed` | 路由器把公网 25565 映射到了内网 25565 而不是 25607 |
| 家宽线路端口探测返回 RST | 包到了路由器但没有匹配的端口映射规则 |
| 家宽线路端口探测超时 | 运营商封了该端口（厦门联通对家宽入站封 80/443/8080/53） |
| QUIC 线路的 frpc 一直连不上 | 节点安全组没放行 UDP 34566。TCP 与 UDP 是分开放行的 |
| 某条 QUIC 线路持续不可用且不自愈 | frp 没有协议自动回退，UDP 被限制后只能手工改回 TCP |
| frpc 报 `port not allowed` | frps 的 `allowPorts` 白名单没包含该 `remotePort` |
| frpc 报 `authorization failed` | token 取错了节点。各节点 token 互不相同 |
| 探针 502 | frps 的 25610 没监听，即 frpc 的 `probe-<线路 id>` proxy 没起来 |
| 探针连上就断/一直挂起 | 探针那条 proxy 误开了 PROXY protocol |
| 探针每分钟断一次 | nginx 的 `proxy_read_timeout` 太短（默认 60s） |
| nginx 报 `unknown directive "http2"` | `http2 on;` 是 nginx 1.25.1 起的语法，节点上是 1.24。本目录的配置不含这一行 |
| nginx 启动报 map 重复定义 | 节点上已有站点定义了同名 map 变量，本目录的配置已用 `$mcwok_probe_upgrade` 规避 |
| shanghai 的 nginx 起不来 | Ubuntu 默认站点要监听 80，与占着 80 的 frps 冲突。删掉 `/etc/nginx/sites-enabled/default` |
| 签证书时节点上别的站点中断 | 用了 `certbot --standalone`，它独占 80。改用 nginx 插件或 webroot |
| shanghai 的测速突然全部失败 | 证书过期。该节点的 DNS-01 续期无法自动完成，见上方续期风险 |
| frps 重启后 `ss` 看不到 25565 | 那些端口由 frpc 注册，等客户端重连后才会重新监听，不是故障 |

关于带宽：家宽上行 500Mbps，20 人满载 Minecraft 约 16Mbps，
带宽在本方案里不构成任何约束，出现卡顿请往上表的方向排查，不要往带宽上想。
