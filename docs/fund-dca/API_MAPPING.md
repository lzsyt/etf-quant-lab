# 最终接口与数据边界

> 历史资料边界（2026-10-29更新）：本文件保留原实施及验收记录。账本、定投计划、定投比较和相关权限现已下线，后续以范围调整与验收（内部资料，未随源码上传）为准；基金预测和分析能力保留。

所有外部接口位于现有Java服务，浏览器会话+CSRF；所有金额/份额/净值采用字符串。用户后续已授权扩展Agent令牌的指定基金只读权限，具体范围见 [Agent权限](../Agent只读访问令牌.md)；令牌不能调用新增写接口。GET无采集、训练、收费或账本副作用。

| 路径 | 方法 | 行为 |
|---|---|---|
| `/api/funds` | GET | 三基金目录、净值新鲜度、独立基金任务状态 |
| `/api/funds/{code}/nav` | GET | 当前净值修订、来源与警告 |
| `/api/funds/{code}/forecasts?horizon=20或60` | GET | 已冻结预测、独立实际评价与模型验证 |
| `/api/forecasts/{id}` | GET | 原始预测、确切输入修订、绑定新闻快照 |
| `/api/funds/{code}/research-summary` | GET | 指定窗口最新预测及当时依据 |
| `/api/funds/{code}/news` | GET | 最新基金新闻快照，与旧预测依据分开 |
| `/api/funds/{code}/tasks` | POST | FUND_NAV/TRAIN/FORECAST/EVALUATE/NEWS；必需幂等键 |
| `/api/fund-portfolios` | GET/POST | 当前用户账户列表/显式初始化账户与可选用户报告草稿 |
| `/api/fund-portfolios/{id}/summary` | GET | 已确认部分、现金、在途、应收、份额与损益 |
| `/api/fund-portfolios/{id}/cash-events` | POST | 期初核实、入金、出金、显式资金分桶 |
| `/api/fund-portfolios/{id}/dividends` | POST | 有凭据的现金/再投资分红 |
| `/api/fund-portfolios/{id}/transactions` | GET/POST | 交易历史/申购或赎回草稿 |
| `/api/fund-transactions/{id}/{action}` | POST | submit/confirm/corrections/refund，服务端状态机 |
| `/api/fund-portfolios/{id}/dca-policy` | GET/PUT | 预算、名义日、可选占比上限及版本 |
| `/api/fund-portfolios/{id}/dca-plans` | GET/POST | 冻结月度计划/候选研究金额/资金约束 |
| `/api/fund-plans/{id}/accept` | POST | 接受指定版本，仅计划不扣款 |
| `/api/fund-portfolios/{id}/exports/transactions.csv` | GET | 当前用户范围CSV，公式注入防护 |
| `/api/dca-comparisons` | GET/POST | 当前用户历史研究列表/固定输入排队 |
| `/api/dca-comparisons/{id}` | GET | 同run三方案指标、现金流与逐笔账本 |
| `/api/tasks/{id}` | GET | 持久任务状态；个人比较任务追加owner校验 |

写入由Idempotency-Key、expected_version和account_kind保护；同键同内容复用，同键不同内容冲突。输入示例见前端 `src/funds` 与Java领域测试，包内示例路径不是最终路由。旧通用POST /api/tasks禁止FUND_前缀，防止绕过新比较入口注入拥有者/行情。

内部Python契约见用户级证据 `output/fund-dca-20261028/engine-contract.json`，内部端口只监听回环并验证X-Internal-Token。新闻报价/分析两端要求相同基金、窗口、证据目录与hash。

2026-10-29 预测读取补充：`/api/funds/{code}/forecasts` 的 `models` 为所选窗口的最新摘要，内部模型工件与训练明细仍保留。顶层 `current_timing` 按预测ID提供读取时的 `observed/required/remaining/status`；其中 `estimated_target_date` 仅是已覆盖官方交易所日历的代理估算，不是基金确认日。原始 `points[].target_date` 和冻结状态不回写；日历不足返回 `CALENDAR_COVERAGE_INSUFFICIENT`，实际评价仍按真实基金净值观测计数。
