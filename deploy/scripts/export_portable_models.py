#!/usr/bin/env python3
"""在原训练环境为既有XGBoost版本补充跨平台制品，不修改模型、预测或历史实验。"""
from pathlib import Path

from etf_quant.models import export_xgboost

ROOT = Path(__file__).resolve().parents[2]


def main():
    """只处理本项目已可信训练的模型文件；不允许上传任意pickle执行。"""
    paths = sorted((ROOT / '.runtime/models').glob('*-xgboost-*.joblib'))
    count = 0
    for path in paths:
        if '.portable.' not in path.name:
            export_xgboost(path)
            count += 1
    print(f'Portable XGBoost artifacts verified: {count}')


if __name__ == '__main__':
    main()
