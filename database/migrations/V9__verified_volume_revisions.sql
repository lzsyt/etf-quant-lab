-- 新浪四个零量日经腾讯未复权OHLC逐项一致性核实；仅修量额，不改变原价格或冻结研究记录。
-- 份数=腾讯手数×100，人民币金额=腾讯万元×10000；证据核验时间为2026-10-24。
-- 原raw_values完整保留，新增volume_revision记录新来源、原值、取得时间与响应摘要。

UPDATE market_bar SET
    raw_values=raw_values || jsonb_build_object('volume_revision',
        '{"provider": "TENCENT_RAW_KLINE", "checked_at": "2026-10-24T12:54:53.873549+00:00", "source_url": "https://proxy.finance.qq.com/ifzqgtimg/appstock/app/newfqkline/get?param=sz159915%2Cday%2C2023-01-01%2C2023-12-31%2C640%2C", "volume_unit": "SHARE", "amount_unit": "CNY", "volume": 657303300, "amount": 1220686900, "raw_sha256": "84ad4d10527281576e27f8bf0e452a9d473640058b24a0808d96f629640976d9"}'::jsonb
        || jsonb_build_object('old_source',source,'old_volume',volume,'old_amount',amount)),
    volume=657303300, amount=1220686900, source='SINA_PRICE_TENCENT_VOLUME_VERIFIED',
    quality_flags=quality_flags-'ZERO_VOLUME_NO_FILL',
    quality_status=CASE WHEN quality_flags-'ZERO_VOLUME_NO_FILL'='[]'::jsonb THEN 'VALID' ELSE 'WARNING' END,
    revision=revision+1
WHERE symbol='159915' AND trade_date='2023-12-12' AND nature='REAL' AND price_basis='RAW'
    AND source='AKSHARE_SINA' AND volume=0 AND coalesce(amount,0)=0
    AND open=1.861 AND high=1.865 AND low=1.851 AND close=1.856;


UPDATE market_bar SET
    raw_values=raw_values || jsonb_build_object('volume_revision',
        '{"provider": "TENCENT_RAW_KLINE", "checked_at": "2026-10-24T12:54:53.873549+00:00", "source_url": "https://proxy.finance.qq.com/ifzqgtimg/appstock/app/newfqkline/get?param=sz159915%2Cday%2C2023-01-01%2C2023-12-31%2C640%2C", "volume_unit": "SHARE", "amount_unit": "CNY", "volume": 531726800, "amount": 971479800, "raw_sha256": "84ad4d10527281576e27f8bf0e452a9d473640058b24a0808d96f629640976d9"}'::jsonb
        || jsonb_build_object('old_source',source,'old_volume',volume,'old_amount',amount)),
    volume=531726800, amount=971479800, source='SINA_PRICE_TENCENT_VOLUME_VERIFIED',
    quality_flags=quality_flags-'ZERO_VOLUME_NO_FILL',
    quality_status=CASE WHEN quality_flags-'ZERO_VOLUME_NO_FILL'='[]'::jsonb THEN 'VALID' ELSE 'WARNING' END,
    revision=revision+1
WHERE symbol='159915' AND trade_date='2023-12-14' AND nature='REAL' AND price_basis='RAW'
    AND source='AKSHARE_SINA' AND volume=0 AND coalesce(amount,0)=0
    AND open=1.833 AND high=1.846 AND low=1.812 AND close=1.812;


UPDATE market_bar SET
    raw_values=raw_values || jsonb_build_object('volume_revision',
        '{"provider": "TENCENT_RAW_KLINE", "checked_at": "2026-10-24T12:54:53.873549+00:00", "source_url": "https://proxy.finance.qq.com/ifzqgtimg/appstock/app/newfqkline/get?param=sz159915%2Cday%2C2023-01-01%2C2023-12-31%2C640%2C", "volume_unit": "SHARE", "amount_unit": "CNY", "volume": 735592200, "amount": 1331806800, "raw_sha256": "84ad4d10527281576e27f8bf0e452a9d473640058b24a0808d96f629640976d9"}'::jsonb
        || jsonb_build_object('old_source',source,'old_volume',volume,'old_amount',amount)),
    volume=735592200, amount=1331806800, source='SINA_PRICE_TENCENT_VOLUME_VERIFIED',
    quality_flags=quality_flags-'ZERO_VOLUME_NO_FILL',
    quality_status=CASE WHEN quality_flags-'ZERO_VOLUME_NO_FILL'='[]'::jsonb THEN 'VALID' ELSE 'WARNING' END,
    revision=revision+1
WHERE symbol='159915' AND trade_date='2023-12-15' AND nature='REAL' AND price_basis='RAW'
    AND source='AKSHARE_SINA' AND volume=0 AND coalesce(amount,0)=0
    AND open=1.820 AND high=1.830 AND low=1.795 AND close=1.802;


UPDATE market_bar SET
    raw_values=raw_values || jsonb_build_object('volume_revision',
        '{"provider": "TENCENT_RAW_KLINE", "checked_at": "2026-10-24T12:54:53.873549+00:00", "source_url": "https://proxy.finance.qq.com/ifzqgtimg/appstock/app/newfqkline/get?param=sz159915%2Cday%2C2024-01-01%2C2024-12-31%2C640%2C", "volume_unit": "SHARE", "amount_unit": "CNY", "volume": 2388576800, "amount": 5027417600, "raw_sha256": "e4a1fe6227a8a2736ad809bd2d4f63ab825d1457c4b53c12cfc84dd0d649a34b"}'::jsonb
        || jsonb_build_object('old_source',source,'old_volume',volume,'old_amount',amount)),
    volume=2388576800, amount=5027417600, source='SINA_PRICE_TENCENT_VOLUME_VERIFIED',
    quality_flags=quality_flags-'ZERO_VOLUME_NO_FILL',
    quality_status=CASE WHEN quality_flags-'ZERO_VOLUME_NO_FILL'='[]'::jsonb THEN 'VALID' ELSE 'WARNING' END,
    revision=revision+1
WHERE symbol='159915' AND trade_date='2024-11-01' AND nature='REAL' AND price_basis='RAW'
    AND source='AKSHARE_SINA' AND volume=0 AND coalesce(amount,0)=0
    AND open=2.104 AND high=2.125 AND low=2.080 AND close=2.084;


-- 保存本次真实抽样范围；修正日期从实际受影响行派生，新空库不会伪称已修复4行。
INSERT INTO market_sync(id,symbol,source,status,started_at,completed_at,report)
SELECT gen_random_uuid(),'159915','TENCENT_CROSS_CHECK','VERIFIED',
    '2026-10-24T12:54:53.873549+00:00'::timestamptz,'2026-10-24T12:54:53.873549+00:00'::timestamptz,
    '{"provider": "TENCENT_RAW_KLINE_VS_AKSHARE_SINA", "checked_at": "2026-10-24T12:54:53.873549+00:00", "sample_count": 11, "start_date": "2026-10-09", "end_date": "2026-10-23", "scope": "仅近期11日OHLC与量额交叉采样，非全历史、非完整分红验证", "price_match": true, "maximum_volume_difference_shares": 41.0, "maximum_amount_difference_cny": 47.0, "unit_evidence": "腾讯手×100、万元×10000；官方AKShare文档和适配源码及33日交叉样本核实", "evidence_path": "market-cross-source/comparison.json"}'::jsonb
    || jsonb_build_object('corrected_dates',(SELECT coalesce(jsonb_agg(trade_date ORDER BY trade_date),'[]'::jsonb)
        FROM market_bar WHERE symbol='159915' AND nature='REAL' AND price_basis='RAW'
        AND source='SINA_PRICE_TENCENT_VOLUME_VERIFIED'));


-- 保存本次真实抽样范围；修正日期从实际受影响行派生，新空库不会伪称已修复4行。
INSERT INTO market_sync(id,symbol,source,status,started_at,completed_at,report)
SELECT gen_random_uuid(),'510300','TENCENT_CROSS_CHECK','VERIFIED',
    '2026-10-24T12:54:53.873549+00:00'::timestamptz,'2026-10-24T12:54:53.873549+00:00'::timestamptz,
    '{"provider": "TENCENT_RAW_KLINE_VS_AKSHARE_SINA", "checked_at": "2026-10-24T12:54:53.873549+00:00", "sample_count": 11, "start_date": "2026-10-09", "end_date": "2026-10-23", "scope": "仅近期11日OHLC与量额交叉采样，非全历史、非完整分红验证", "price_match": true, "maximum_volume_difference_shares": 36.0, "maximum_amount_difference_cny": 47.0, "unit_evidence": "腾讯手×100、万元×10000；官方AKShare文档和适配源码及33日交叉样本核实", "evidence_path": "market-cross-source/comparison.json"}'::jsonb
    || jsonb_build_object('corrected_dates',(SELECT coalesce(jsonb_agg(trade_date ORDER BY trade_date),'[]'::jsonb)
        FROM market_bar WHERE symbol='510300' AND nature='REAL' AND price_basis='RAW'
        AND source='SINA_PRICE_TENCENT_VOLUME_VERIFIED'));


-- 保存本次真实抽样范围；修正日期从实际受影响行派生，新空库不会伪称已修复4行。
INSERT INTO market_sync(id,symbol,source,status,started_at,completed_at,report)
SELECT gen_random_uuid(),'510500','TENCENT_CROSS_CHECK','VERIFIED',
    '2026-10-24T12:54:53.873549+00:00'::timestamptz,'2026-10-24T12:54:53.873549+00:00'::timestamptz,
    '{"provider": "TENCENT_RAW_KLINE_VS_AKSHARE_SINA", "checked_at": "2026-10-24T12:54:53.873549+00:00", "sample_count": 11, "start_date": "2026-10-09", "end_date": "2026-10-23", "scope": "仅近期11日OHLC与量额交叉采样，非全历史、非完整分红验证", "price_match": true, "maximum_volume_difference_shares": 40.0, "maximum_amount_difference_cny": 49.0, "unit_evidence": "腾讯手×100、万元×10000；官方AKShare文档和适配源码及33日交叉样本核实", "evidence_path": "market-cross-source/comparison.json"}'::jsonb
    || jsonb_build_object('corrected_dates',(SELECT coalesce(jsonb_agg(trade_date ORDER BY trade_date),'[]'::jsonb)
        FROM market_bar WHERE symbol='510500' AND nature='REAL' AND price_basis='RAW'
        AND source='SINA_PRICE_TENCENT_VOLUME_VERIFIED'));
