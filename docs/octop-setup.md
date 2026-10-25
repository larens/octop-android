# Octop 服务端配置要点

客户端本身只是壳，真正决定体验的是服务端。以下四点是网友最常卡住的地方。

## 1. 公网可访问 + HTTPS

客户端默认拒绝明文 HTTP。请为你的 Octop 实例配上受信任的证书（Let's Encrypt 即可）：

```bash
certbot --nginx -d octop.example.com
```

自签证书不会被客户端信任（我们不放宽 `onReceivedSslError`，这是刻意的安全选择）。内网自建的用户可以在客户端里打开「允许明文 HTTP」开关，但仅限内网可信环境。

## 2. 反向代理要正确处理 WebSocket 与 SSE

Octop 后端监听 `127.0.0.1:8088`，必须经 Nginx 反代暴露。流式输出依赖 SSE，**`proxy_buffering` 没关会出现「消息发出后一直转圈、最后一次性刷出全部内容」**。

```nginx
map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}

server {
    listen 443 ssl;
    server_name octop.example.com;

    ssl_certificate     /etc/letsencrypt/live/octop.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/octop.example.com/privkey.pem;

    client_max_body_size 512m;      # 附件上传

    location / {
        proxy_pass http://127.0.0.1:8088;
        proxy_http_version 1.1;

        # WebSocket
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;

        # SSE：必须关闭缓冲，否则流式输出失效
        proxy_buffering off;
        proxy_cache off;

        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;

        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

改完 `nginx -t && systemctl reload nginx`。

## 3. 把 agent 的会话模式从 `ask` 改成 `craft`（关键）

**「手机上只能问、不能派活」的根因不是客户端，而是 agent 的 `conversation_mode`。**
飞书渠道默认常被设为 `ask`（只读问答）；`craft` 才允许真实执行工具、写文件、跑命令。

**它存在哪里**：不在独立的列上，而是 `agents.config_json` 这个 JSON 里的 `conversation_mode` 字段（另有按会话覆盖的 `threads.conversation_mode`，为空时回退到 agent 的配置）。

两种改法，任选其一：

**方式 A：dashboard（推荐）**

打开 `https://你的域名`，进入对应 agent 的设置页，把会话模式改为 `craft`，保存。

**方式 B：直接改 SQLite**

```bash
cd /opt/octop
systemctl stop octop
cp octop.db "octop.db.bak.$(date +%Y%m%d%H%M%S)"

/opt/octop/venv/bin/python - <<'PY'
import sqlite3, json

con = sqlite3.connect("/opt/octop/octop.db")
cur = con.cursor()

# 先看清楚现状
for agent_id, name, cfg in cur.execute("SELECT agent_id, name, config_json FROM agents"):
    print(agent_id, name, json.loads(cfg or "{}").get("conversation_mode"))

# 改全部 agent；只想改一个就加 WHERE agent_id = '你的agent_id'
for agent_id, cfg in cur.execute("SELECT agent_id, config_json FROM agents"):
    data = json.loads(cfg) if cfg else {}
    data["conversation_mode"] = "craft"
    cur.execute(
        "UPDATE agents SET config_json = ? WHERE agent_id = ?",
        (json.dumps(data, ensure_ascii=False), agent_id),
    )

# 顺手清掉按会话的旧覆盖值，避免它继续生效
cur.execute("UPDATE threads SET conversation_mode = NULL WHERE conversation_mode = 'ask'")

con.commit()
con.close()
PY

systemctl start octop
```

改完确认一下：

```bash
/opt/octop/venv/bin/python -c "
import sqlite3, json
con = sqlite3.connect('file:/opt/octop/octop.db?mode=ro', uri=True)
for a, n, c in con.execute('SELECT agent_id, name, config_json FROM agents'):
    print(a, n, json.loads(c)['conversation_mode'])
"
```

> 不同 Octop 版本的 schema 可能有差异，改之前先用 `PRAGMA table_info(agents);` 确认字段确实存在。

## 4.（仅 TWA 用户）配置 assetlinks.json

TWA 靠 Digital Asset Links 校验「这个 APK 属于这个域名」。校验失败时 **Chrome 不报错，只是静默降级成带地址栏的 Custom Tab** —— 很多人以为成功了其实没有。

部署步骤：

1. 运行 `./scripts/gen-assetlinks.sh <包名> <域名>`，得到 `build-assetlinks/assetlinks.json`。
2. 放到站点根目录的 `.well-known/` 下，确保最终可访问：
   `https://你的域名/.well-known/assetlinks.json`
3. Nginx 片段（**必须放在 `location /` 之前**，且只需写在 443 块里，Chrome 只走 HTTPS 请求）：

```nginx
location = /.well-known/assetlinks.json {
    default_type application/json;
    alias /var/www/octop/.well-known/assetlinks.json;
}
```

4. 校验：

```bash
curl -s "https://digitalassetlinks.googleapis.com/v1/statements:list?source.web.site=https://你的域名&relation=delegate_permission/common.handle_all_urls"
```

返回的 `sha256_cert_fingerprints` 必须与 `keytool -list -v -keystore keystore/octop-release.jks -alias octop | grep SHA256` 完全一致（大小写不敏感，冒号可有可无）。

### 反向验证排查方法

故意改错一个字符再访问，如果应用**变成带地址栏**，说明你的排查路径是对的，之前的「无地址栏」确实是校验通过的结果；如果改错后仍然无地址栏，通常是：
- 改错了文件（不是站点根路径下的 `.well-known/assetlinks.json`）；
- 反代缓存了旧响应；
- 或 Nginx 的 `location =` 被后面的 `location /` 覆盖（顺序问题）。

## 5. 常见问题

| 现象 | 原因 |
|------|------|
| 消息发出后长时间无响应，最后一次性刷出 | Nginx 未关闭 `proxy_buffering`（SSE 被缓冲） |
| 重启客户端就要重新登录 | 服务端 Cookie 未设 `SameSite`/`Secure`，或客户端被清除数据 |
| 上传附件返回 413 | `client_max_body_size` 太小 |
| 手机上只能问答，不让执行 | agent `conversation_mode` 仍为 `ask`（见第 3 节） |
| TWA 有地址栏 | assetlinks 未生效（见第 4 节） |
