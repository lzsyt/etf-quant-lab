"""融合验证不借用测试标签选择权重。"""

from datetime import date, timedelta

from etf_quant.fusion import FusionRequest, FusionRow, evaluate_fusion


def test_fusion_requires_real_forward_history():
    assert evaluate_fusion(FusionRequest(rows=[]))["status"] == "PENDING_FORWARD_EVIDENCE"


def test_fusion_weights_are_unchanged_when_test_labels_change():
    rows = [
        FusionRow(
            signal_date=date(2025, 1, 1) + timedelta(days=i),
            target_date=date(2025, 1, 1) + timedelta(days=i + 5),
            ml_probability=0.6 if i % 2 else 0.4,
            sentiment=0.3 if i % 3 else -0.3,
            actual_up=bool(i % 2),
        )
        for i in range(200)
    ]
    before = evaluate_fusion(FusionRequest(rows=rows))
    for row in rows[160:]:
        row.actual_up = not row.actual_up
    after = evaluate_fusion(FusionRequest(rows=rows))
    assert before["ml_weight"] == after["ml_weight"]
    assert before["fusion"]["accuracy"] != after["fusion"]["accuracy"]
