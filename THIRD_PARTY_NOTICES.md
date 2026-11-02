# 第三方来源与许可说明

根目录 `LICENSE` 中的 MIT 许可适用于本项目自有代码及文档，不改变第三方代码、依赖、内容、商标和服务的权利归属。下列材料中的第三方原文、引用及相应输入不由本项目重新授权；这是来源说明，不是数据集许可证。

## 第三方依赖

Java、Python、前端及部署工具的第三方依赖分别适用其上游许可证。依赖清单位于 `backend/pom.xml`、两个 Python 模块的 `pyproject.toml`、`frontend/package-lock.json` 和 `deploy/windows/requirements-windows.lock`。本仓库没有随源码打包这些依赖的安装包或二进制文件；构建、部署或分发包含依赖的制品时，仍须保留并满足对应版本的许可证和通知要求。本说明不替代完整的制品依赖许可清单。

## 历史回归材料中的来源

|仓库材料|来源与说明|
|---|---|
|`ai-analysis-service/tests/fixtures/forward_v21_real_failure.json`、`forward_v211_real_failure.json`|包含引自国家统计局网站（https://www.stats.gov.cn）及中华人民共和国财政部网站（https://www.mof.gov.cn）的公开资料摘录，原文地址保留在 `source_url`。仅用于重放历史模型失败、检验证据归属及校验规则；不能将失败输出视为原机构结论。|
|`ai-analysis-service/tests/fixtures/fund_007169_identity_mismatch_20261029.json`|包含华夏基金公开季度报告的有限引用，以及与基金身份不匹配的历史模型输出，原始来源地址保留在 `saved_facts[].source_url`。该材料用于验证错误归属能被拒绝，不能作为目标基金的有效研究结论。|

国家统计局的转载与引用要求以其[服务条款](https://www.stats.gov.cn/wzgl/202302/t20230217_1912857.html)为准，包括显著注明来源、保持原意及不擅自替原站授权他人继续转载。财政部网站注明转载需注明来源，可在[官方页面](https://www.mof.gov.cn/zhuantihuigu/index_1.htm)核对。华夏基金报告的版权归原权利人，本项目未获得将报告另行按 MIT 许可分发的授权；[报告原始地址](https://www.chinaamc.com/front/front/file/preview/1836c7f6ccab9237e3f4a155320aab9a/%E5%8D%8E%E5%A4%8F%E9%BB%84%E9%87%91%E4%BA%A4%E6%98%93%E5%9E%8B%E5%BC%80%E6%94%BE%E5%BC%8F%E8%AF%81%E5%88%B8%E6%8A%95%E8%B5%84%E5%9F%BA%E9%87%91%E5%8F%91%E8%B5%B7%E5%BC%8F%E8%81%94%E6%8E%A5%E5%9F%BA%E9%87%912026%E5%B9%B4%E7%AC%AC3%E5%AD%A3%E5%BA%A6%E6%8A%A5%E5%91%8A.pdf)用于核对引用出处。

## 数据源和服务

应用运行时取得的行情、净值、黄金数据、新闻、公告与供应商输出不因本项目开源而自动获得额外许可。使用者应按各来源当时适用的访问、引用、展示和再分发条款取得及使用；项目不随源码提供运行数据库、下载缓存或模型工件。历史来源文档是当时核验记录，不代表来源永久允许采集或商业再分发。

项目名称、代码开放及来源链接不表示任何基金公司、数据机构或模型供应商对本项目提供认可或背书。
