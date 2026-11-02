# Agent 只读访问令牌

适用范围：源码 `f23cfbf` 及本轮发布 `20261029-122015-f23cfbf`。账本、定投政策和计划已下线，旧账本只读授权撤销；基金预测和新闻研究权限保留。变化与验证见下线验收（内部资料，未随源码上传）。历史令牌验收仅代表当时版本。

管理员登录后在“系统管理 → Agent 访问令牌”填写名称及 1–365 天有效期。创建成功后明文只显示一次，应立即保存到调用方的受保护配置；列表仅显示名称、时间和状态，撤销立即生效。令牌泄露时立即撤销并重新创建。到期时间在界面按北京时间显示，数据库按 UTC 存储。请求通过 HTTPS 的 `Authorization: Bearer <访问令牌>` 请求头携带，不要放入 URL、日志或源码。

例如（调用方从自身受保护环境变量提供值）：

```bash
curl -H "Authorization: Bearer $ETF_AGENT_TOKEN" https://etf.example.invalid/api/backtests
```

运行状态以目标环境的最新核查为准。返回 401 表示令牌无效、过期或撤销，403 表示接口或方法不在授权范围。页面本身仍使用管理员会话；Agent 直接读取页面对应的数据接口，不能用令牌管理其他令牌或执行任务。

当前白名单仅允许 GET：

- 总览 `/api/dashboard`；行情 `/api/market/instruments`、`bars`、`quality`、`quality-report`、`indicators`。
- 预测和情报 `/api/predictions`、`/api/news`、`/api/analyses`、`/api/models`、`/api/intelligence`。
- 回测 `/api/backtests`、`/api/backtests/{id}`；策略 `/api/strategies`；比较 `/api/rankings`、`/api/rankings/forward`、`/api/fusion/evaluation`。
- 模拟账户 `/api/paper/accounts`、`positions`、`orders`、`fills`、`snapshots`、`reconstruction`、`diagnostics`；风控 `/api/risk`、`/api/risk/events`。
- 报告 `/api/reports`、`/api/reports/{id}`；前瞻 `/api/forward`；黄金 `/api/gold/overview`、`/api/gold/predictions/{id}`。
- 基金目录 `/api/funds`；三只基金的 `/api/funds/{code}/nav`、`forecasts`、`news`、`research-summary`，以及 `/api/forecasts/{id}`。

上述同一组路径中省略前缀的项沿用前一项所在目录。查询参数和返回字段与现有网页接口一致；例如 `/api/paper/positions?account=lightgbm`。系统管理、操作审计、任务、黄金成本计算及全部写操作不在令牌授权范围内。黄金成本计算虽无持久化副作用，但使用 POST，本版不开放。

令牌使用随机 256 位秘密，数据库只保存 SHA-256 摘要，每次请求检查数据库中的有效期和撤销状态；创建响应标记禁止缓存。有效令牌拥有上述全部研究数据的读取权限，应为不同应用分别创建并限定期限。密码重置不会自动撤销独立令牌，需要管理员主动撤销。

Bearer认证仅在当次请求内有效，不持久化为浏览器登录会话；即使复用响应Cookie，后续不带令牌请求也必须正常登录。调用方应在每次请求中提供有效令牌。
