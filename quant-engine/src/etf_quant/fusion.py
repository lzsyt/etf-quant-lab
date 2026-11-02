"""正式融合评估只使用已到期的前瞻样本，按日期学习权重并隔离测试集。"""

from datetime import date

import numpy as np
from pydantic import BaseModel, Field
from sklearn.linear_model import LogisticRegression

from etf_quant.models import metrics


class FusionRow(BaseModel):
    signal_date: date
    target_date: date
    ml_probability: float = Field(ge=0, le=1)
    sentiment: float = Field(ge=-1, le=1)
    actual_up: bool


class FusionRequest(BaseModel):
    rows: list[FusionRow]


def evaluate_fusion(request: FusionRequest):
    """训练段校准事件分数，验证段选择混合权重；最后20%只报告一次样本外结果。"""
    rows = sorted(request.rows, key=lambda row: row.signal_date)
    dates = sorted({row.signal_date for row in rows})
    if len(dates) < 150:
        return {
            "status": "PENDING_FORWARD_EVIDENCE",
            "available_dates": len(dates),
            "required_dates": 150,
            "fixed_weight_is_experimental": True,
        }
    validation_start, test_start = dates[int(len(dates) * 0.6)], dates[int(len(dates) * 0.8)]
    train = [
        r for r in rows if r.signal_date < validation_start and r.target_date < validation_start
    ]
    validation = [
        r
        for r in rows
        if validation_start <= r.signal_date < test_start and r.target_date < test_start
    ]
    test = [r for r in rows if r.signal_date >= test_start]
    if any(len({r.actual_up for r in group}) < 2 for group in [train, validation, test]):
        return {"status": "INSUFFICIENT_CLASS_VARIATION"}
    event = LogisticRegression(random_state=42).fit(
        np.array([[r.sentiment] for r in train]), [r.actual_up for r in train]
    )
    event_validation = event.predict_proba([[r.sentiment] for r in validation])[:, 1]
    validation_ml = np.array([r.ml_probability for r in validation])
    candidates = []
    for weight in np.linspace(0, 1, 21):
        mixed = weight * validation_ml + (1 - weight) * event_validation
        candidates.append(
            (metrics([r.actual_up for r in validation], mixed)["brier_score"], weight)
        )
    _, selected = min(candidates)
    ml = np.array([r.ml_probability for r in test])
    event_test = event.predict_proba([[r.sentiment] for r in test])[:, 1]
    mixed = selected * ml + (1 - selected) * event_test
    baseline = metrics([r.actual_up for r in test], ml)
    fusion = metrics([r.actual_up for r in test], mixed)
    return {
        "status": "EVALUATED",
        "evaluation_type": "FORWARD_HELD_OUT",
        "method": "EVENT_TRAIN_CALIBRATION_VALIDATION_GRID_WEIGHT",
        "ml_weight": float(selected),
        "llm_weight": float(1 - selected),
        "validation_start": str(validation_start),
        "test_start": str(test_start),
        "ml": baseline,
        "llm_calibrated": metrics([r.actual_up for r in test], event_test),
        "fusion": fusion,
        "brier_improvement": baseline["brier_score"] - fusion["brier_score"],
        "statement": "样本外差值按实际结果报告，不预设融合更好；尚非统计显著性结论",
    }
