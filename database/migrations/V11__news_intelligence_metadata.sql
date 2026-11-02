-- 新闻增强信息独立追加，原 news_item、冻结分析、预测及四账户保持原样。
-- 同一新闻快照首次采集时固定增强信息；重复采集不更新旧快照或首次获取时间。
CREATE TABLE news_item_metadata (
    news_id text PRIMARY KEY REFERENCES news_item(id),
    details jsonb NOT NULL
);
