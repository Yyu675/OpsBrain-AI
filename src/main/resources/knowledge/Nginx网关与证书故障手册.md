# Nginx 网关与证书故障手册

> 适用范围：502 Bad Gateway 排查、Nginx 502 常见原因、SSL 证书到期处置、网关超时调优、证书过期导致的故障。
> 两心法：502 的错误**一定在上游**，网关只是报信人；证书故障**一定有提早量**——
> 等到过期当天才处置的证书，本质是排班表事故。

---

## 1. 502 Bad Gateway 排查

### 问题描述
客户端经网关/代理访问返回 502 Bad Gateway，直连后端正常或不正常不定。

### 常见原因（按先查顺序排）
1. **上游进程挂了或端口没听**：后端 OOMKill/未启动/监听口写错
2. **上游挂了但 socket 残留**：连接池拿到半死连接（kept-alive 对上崩溃）
3. **上游超时**：后端 BLOCK 超 `proxy_read_timeout` 网关主动断
4. **上游拒绝/重置连接实例**：backlog 满、worker_connections 打爆
5. **DNS/LB 指向漂移**：上游地址解析到旧实例/下线的 Pod
6. **大响应头/体熔断**:proxy_buffer 太小，上游响应超 buffering 窗口被拒

### 排查步骤

#### 1. 先定位错误的归属（错误日志优先，不看 access log 猜）
```bash
tail -50 /var/log/nginx/error.log
# 关键指纹:
#   connect() failed (111: Connection refused)  → 上游没听(原因1)
#   upstream prematurely closed connection       → 上游崩/半死连接(原因2)
#   upstream timed out (110: Connection timed out) → 超时(原因3)
#   recv() failed (104: Connection reset by peer)  → 上游重置(原因4)
```

#### 2. 绕过网关直连定界
```bash
# 从 网关 机器上打上游,不等响应则说明是慢,不是 502 的锅
curl -sv -o /dev/null -m 5 http://<upstream-host>:<port>/health
ss -tlnp | grep <port>          # 上游机器:口听没听
```
三类结局定权：直连 200 → 网关配置问题；直连超时 → 上游健康但慢（去 §4);
直连拒绝/无听 → 上游病（去后端查进程/日志）。

#### 3. K8s 场景追加
```bash
kubectl get pods -l app=<svc> --wide            # pod 阶段与重启计数
kubectl get endpoints <svc>                     # 服务后面挂的实例漂移没有
kubectl describe pod <pod> | tail -30           # OOMKilled/探针失败事件
```

### 解决方案
| 病灶 | 处置 |
| --- | --- |
| 上游 OOMKill 反复重启 | 内存上限与 JVM MaxRAMPercentage 互证（预算先行），别只加重启 |
| 半死连接 | `proxy_http_version 1.1` + `proxy_set_header Connection ""` + 上游 keepalive_timeout ≥ 网关侧 |
| upstream 超时被断 | `proxy_read_timeout` 合理上调（见 §4)，同时治上游慢本身 |
| 连接打爆 | worker_connections + ulimit 配套查；llimits 只看 nginx.conf 不够，看 systemd unit 的 LimitNOFILE |

### 验证方法
`error.log` 同型指纹 **1 小时零出现** + 直连/网关双侧 1 同一探测 200 五次全中——
两者缺一不算回生，只在 access log 里看不到 502 可能风暴还没来。

---

## 2. Nginx 502 常见原因速查表

| 指纹（error.log) | 病因 | 优先级 |
| --- | --- | --- |
| 111 Connection refused | 上游没听/进程死 | P0 |
| 110 Connection timed out | 网络分区/防火墙丢包 | P0 |
| 104 Connection reset | 上游崩/拒绝实例 | P1 |
| upstream prematurely closed | 上游崩溃中重启环 | P1 |
| upstream timed out (reading) | 上游处理超 proxy_read_timeout | P2 |
| no live upstreams | upstream 块里所有 peer 被标记下线 | P0 |
| too big header/body buffering | proxy_buffer 不足 | P3 |

排班提示：「重启 502 没了」不是修好——半数原因是上游崩溃环被重启对齐节奏，
留 error.log 指纹截图进工单，下次复发对得上周五还是配置漂移。

---

## 3. SSL 证书快到期处置

### 问题描述
监控/浏览器提示证书即将到期（< 30 天），需要续期且无中断切量。

### 处置（分证书来源）
```bash
# 0. 先看现状与剩余天数(行权面前先量)
echo | openssl s_client -connect host:443 -servername host 2>/dev/null \
  | openssl x509 -noout -dates -issuer

# 1. Let's Encrypt:交给自动化的才算不疼
certbot renew --dry-run                     # 干跑:DNS/端口/账户三件事
certbot renew && nginx -s reload            # reload 不断连接,restart 会
# 2. 商用/内部 CA:备新证书签发流程,DNS 验证优先于 HTTP 验证(内网穿透少一环)
```

### 切换四步（任何来源通用）
1. 新证书+链文件就位，权限 600;
2. `nginx -t` 配置语检 + `openssl x509 -in new.crt -noout -dates -subject` 物检;
3. `nginx -s reload` 热换；
4. **发起方验证**:s_client 日期为新证，连续 3 个外部入口（含移动端/WAF 前置）抽查。

### 防再犯（接受一次性动作，不接受重复动作）
- 到期前 30/14/7 天三级告警接进现有告警链（Prometheus `probe_ssl_earliest_cert_expiry`
  黑盒探测）——比日历提醒有用的唯一原因是它随系统上线;
- 多域名证书建清单档（域名/CA/DNS 供应商/自动续期否），半年复核一次。

---

## 4. 网关超时配置调优

```nginx
location / {
    proxy_connect_timeout 5s;     # 建连:内网不该有一秒以上,别给 60s 养慢病
    proxy_send_timeout    60s;    # 发送请求体
    proxy_read_timeout    120s;   # 读响应:按上游真实 P99 ×2 给,不给拍脑袋值
    proxy_next_upstream error timeout http_502 http_503;
    proxy_next_upstream_tries 2;  # 重试只重试幂等 GET,POST 重试=重复写风险
}
```

三调优戒律：
1. **给上游设时间是给下游买保险**：网关为读慢兜底 ×2,客户端再为网关兜底 ×2——
   三级超时不等比，超时会穿透成雪花 502;
2. **重试只配给幂等**:`proxy_next_upstream` 对 POST/写接口默认隐形开启=埋雷;
3. SSE/长连接接口单列 location 关 buffering(`proxy_buffering off;`
   `X-Accel-Buffering: no`)，不然流式变批处理。

---

## 5. 证书过期会导致什么故障（认知清单）

1. **全站不可信**：浏览器整页拦截，API 客户端 `SSLHandshakeException`——
   业务层面等价于全站 DOWN,P0;
2. **级联超时假象**：客户端重试风暴把网关打到 backlog 满，错误呈现为超时，根因在证书;
3. **webhook/回调失联**：第三方回调验签失败静默停发——业务单据停摆但页面正常
   （告警链自己用 HTTPS 更痛：告警都发不出去）;
4. **mTLS 内部域同样适用**：服务网格内双向过期，海量 503 而非浏览器红页，定位绕一圈。
