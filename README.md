# ETF Quant Lab

基金、黄金与 ETF 的预测分析研究平台，包含历史回测、模拟实验、独立预测与证据展示。仅用于研究和模拟交易，不连接真实证券账户或资金渠道。

## 源码范围

- `backend/`：Java 17 / Spring Boot 后端。
- `frontend/`：Vue 3、TypeScript、Element Plus、ECharts。
- `quant-engine/`：Python 3.12 / FastAPI，LightGBM、XGBoost及量化研究。
- `ai-analysis-service/`：DeepSeek 与 GPT 独立预测、新闻证据及身份校验。
- `database/migrations/`：完整 Flyway 数据库迁移。
- `deploy/`：本地与 Windows Server 部署脚本及依赖锁。
- `tests/`：接口、研究、迁移与运维测试；各模块也包含单元测试。
- `docs/`：脱敏后的架构、研究协议和接口资料。部分文档记录历史设计，当前行为以源码为准。

当前代码已修复基金新闻材料归属与明确投资机制冲突，并停用 GPT 分歧分析；GPT 保留独立预测。旧分歧记录及原始基金证据保持可追溯。

## 配置与构建

使用 Java 17、Python 3.12、Node.js 22 和 PostgreSQL 16。Python依赖声明见两个Python模块的 `pyproject.toml`；Windows版本锁定见 `deploy/windows/requirements-windows.lock`。前端通过 `npm ci`、`npm run build` 构建，后端通过 `mvn -f backend/pom.xml package -DskipTests` 打包。运行Java集成测试需先配置独立的 `etf_quant_lab_test` 数据库。

数据库、管理员口令摘要、内部令牌及供应商密钥从受保护配置注入，变量声明见 `backend/src/main/resources/application.yml` 和分析服务代码。真实密钥、账号密码、数据库、日志、模型工件与运行目录不随仓库提供；按实际环境单独配置，禁止提交。

部署及现场验收脚本中的目标地址已经替换为 `example.invalid`，网站为 `etf.example.invalid`；个人目录使用 `/home/developer` 示例。运行前必须通过脚本参数或本机配置设置自己的目标。部署与现场验收脚本可能写数据库、重启服务或产生模型费用，不应当作纯只读测试运行。

## 上传与历史

这是当前完整代码的脱敏快照。原始开发Git历史、内部生产验收与恢复档案、个人机器资料保留在原环境；本仓库从脱敏快照开始版本管理。未上传任何运行凭据，供应商API只保留环境变量引用，测试占位凭据不具备真实访问权限。

本地基线验证：Java288项零失败（3项条件跳过）、Python380项及前端44项通过，前后端构建成功。上传副本另做语法、配置格式与敏感信息复核；不声称已在GitHub或新部署环境完成真实数据、生产或付费验收。
