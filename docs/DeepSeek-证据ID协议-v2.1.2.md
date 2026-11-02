# DeepSeek 新闻证据 ID 协议 V2.1.2

> 后续真实验证补充：2026-10-25 13:47专项单批已通过，三个ETF合法信息不足，随后恢复原门禁。本文描述的发布时“本轮不追加收费”是此前阶段边界；协议本身未因单批放行改变。实际记录见真实补充验收（内部资料，未随源码上传），连续背景见第12章（内部资料，未随源码上传）。

适用范围：逐 ETF、h=1 的前瞻新闻请求。旧历史输出和旧失败记录不转换、不回填。数据库继续 Flyway V11。

## 版本与职责

- prompt：`etf-symbol-h1-v2.1.3`；上一部署为 `etf-symbol-h1-v2.1.2`。
- schema：`etf-symbol-evidence-v2.1.2`；上一部署为 `etf-symbol-events-v2.1.1`。
- evidence：`news-evidence-v1`；请求身份：`etf-news-request-v2`。
- Python 提取并保存原片段、编号和校验；DeepSeek 只选择 `evidence_ids`，返回金融影响推断，不复制 `facts.quote`、不搜索、不执行交易。
- `SourceFact`/旧批次及 `validate_legacy_batch` 只保留用于历史离线重放，正式 run 仅使用新证据结构。

## 确定性证据

沿用本地分类、事件聚合、重要性排序和1600字符模型正文上限。最终选入来源的每一字段独立分段，最多4条证据/篇，不跨标题、摘要、正文拼接。

`evidence.py` 使用自然句结束位置分组；重复正文前缀摘要不重复发送，有完整句时舍弃不完整尾句并保存原因。无法分句的原片段或数字密集/表格内容保留连续上下文，标记 `standalone=false, context_required=true`。标题/摘要类型为SUMMARY，正文证据一律EXCERPT，另存来源原类型。标题的standalone仅表示可独立阅读，不能证明h1有效。

扁平化GDP表格不进行指标、季度、数值和单位配对，不补字、不补数字、不语义重写；最多保留原1600字符上下文。若语义不足，模型应合法返回INSUFFICIENT_DATA。ID存在不能证明模型对表格的理解或金融预测正确。

输入快照SHA256由证据版本、北京决策日、horizon、品种集合、按来源ID排序的原字段、来源URL/名称/原内容哈希、双时间戳、核验状态和内容类型等规范JSON构成。同北京日同一输入可重复计算；cutoff精确时刻仍由输入消息SHA256单独审计，实际筛选总是先核查真实cutoff。

证据ID为 `EV_` 加完整SHA256，绑定快照、source_id、字段名、Unicode字符半开位置[start,end)及原切片UTF-8哈希。`text`始终等于原字段切片，不规范化原文。原采集content_sha256沿用，不按截断正文错误重算；`text_sha256`校验当前切片。

目录保存在既有 `forward_analysis.quote.evidence_catalog` JSONB；原新闻仍在 `input_snapshot.news`，无需迁移。目录字段包括id、source_id、field、start/end、text、text_sha256、source_content_sha256、published_at/fetched_at、content_type、source_content_type及上下文标记。

## 模型输入与输出

输入为来源元数据、ETF允许来源、证据ID及原片段。模型消息不再重复携带新闻title/summary/content三份文本；原完整审计目录不全量送模。发布时间、抓取时间与出处保留在来源元数据，证据通过source_id关联。

每ETF保持symbol、horizon、status、relevant_source_ids、sentiment_score、risk_level、rationale、relevance_level、direction、impact_logic、uncertainty；`facts`替换为`evidence_ids`，最多4个、优先1个。strict extra forbid 拒绝额外facts/quote或模型生成目录。

| 状态 | 分数/风险 | 引用与方向 |
|---|---|---|
| AVAILABLE | 有限[-1,1]分数及low/medium/high | 有来源和证据；positive>0、negative<0、neutral=0；mixed解释相反渠道；不得unknown |
| INSUFFICIENT_DATA | 均null | 有相关来源，可保留有效证据；方向unknown |
| NO_RELEVANT_NEWS | 均null | 来源与证据均空，相关性none、方向unknown |
| FAILED（批次） | 不提供交易因子 | API、结构或证据校验失败，保留原始响应及费用 |

每只请求ETF恰好一次、horizon只能1。重新构建证据校验快照未变化；未知/重复/跨快照ID、来源未关联、跨ETF资格、未来或未核验材料均拒绝。不能通过静默改分数、方向或补原文修复输出。金融语义约束继续独立校验；ID真实不保证推断真实、价格可预测或收益改善。

## 冻结身份、费用与Java

Java quote后把 `expected_input_sha256` 与 `expected_evidence_snapshot_id` 固定到run载荷。真实run缺少任一项或重建身份不一致时，供应商请求前返回 `FAILED/INPUT_SNAPSHOT_MISMATCH`，attempts=0、费用0。隔离Mock可省略；提供时同样必须配对匹配。

Java核对新schema证据版本和快照身份，并核对既有prompt/schema/model/input身份；不匹配的结果不融合，实际费用证据不丢。业务task幂等、请求缓存、失败复用和显式版本验证门禁保持上一版：不同身份仅获得新版本资格判断，不自动获得额外收费授权。

费用仍1 USD/日、最多2次HTTP/批、输出1400上限；正常日额度和一次显式版本验证额度不扩大。2026-10-25本轮基线已2个付费批次，其中1个显式验证，本轮不追加收费。仅报价可保存MISSING及`DAILY_CALL_LIMIT_CURRENT_REQUEST_NOT_VALIDATED`，对应WAITING_REAL_VALIDATION。

预算仍按实际消息UTF-8字节加1024余量计算，总上界24000；不是模型实际Token计数。每次预算删除来源后重新计算证据目录和ID，避免引用被剔除内容。完整相同请求重复不计费，新协议不错误复用旧失败为新版验证成功。

Java只对REAL、SUCCEEDED且单ETF AVAILABLE的结果提供新闻因子。合法信息不足属于结构成功但无交易因子，继续ML_FALLBACK；AVAILABLE的S=0仍有效，risk=high仍由原风险否决执行。四账户、70/30、阈值0.1、仓位、撮合、T+1和旧冻结预测均不修改。

## 管理页面与历史

财经情报和前瞻页从顶层冻结quote目录按ID解析，模型facts和模型伪造目录不作为新证据。只有冻结源名称及域名同时匹配现有准入官方来源才公开最多240 Unicode字符；完整原片段留在受保护快照。公开display_text可能截短，不冒称它能重新计算完整text_sha256。

页面分别呈现冻结事实、模型推断和不确定性。仅报价的目录标“尚非模型引用”；新版本未调用或日门禁阻断标WAITING_REAL_VALIDATION，旧FAILED照实展示，不填模拟情绪。历史旧版事实展示保持兼容，不重写历史。

隔离Mock成功、真实HTTP成功、严格结构通过、AVAILABLE因子、Java消费、新前瞻决策使用、模拟收益改善必须分别验收，不得互相替代。
