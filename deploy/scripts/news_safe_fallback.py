"""基于现有V2发布包制作只关闭新闻能力的独立回退包，绝不改已有发布目录。"""

import argparse
import ast
import hashlib
import json
from pathlib import Path
import subprocess
import time
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def main():
    """保留旧交易制品逐字节不变，仅替换采集入口与模型启用开关并记录摘要。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError("回退发布包已存在，拒绝覆盖")
    with zipfile.ZipFile(args.base) as original:
        files = {name: original.read(name) for name in original.namelist()}
    base = json.loads(files["release.json"])
    news_path = "ai-analysis-service/src/etf_ai/news.py"
    provider_path = "ai-analysis-service/src/etf_ai/providers.py"
    source = files[news_path].decode()
    function = next(node for node in ast.parse(source).body if isinstance(node, ast.FunctionDef) and node.name == "collect_news")
    replacement = '''def collect_news():
    """回退模式禁止旧新闻网络请求；保留四账户及既有ML_FALLBACK。"""
    now = datetime.now(UTC).isoformat()
    return {"items": [], "completed_at": now, "sources": [
        {"source": name, "status": "DISABLED", "count": 0,
         "candidate_count": 0, "relevant_count": 0, "fetched_at": now,
         "error_code": "SAFE_ROLLBACK_NEWS_DISABLED"}
        for name in ("PBOC", "SINA_FINANCE", "SINA_FUND")
    ]}
'''
    lines = source.splitlines(keepends=True)
    files[news_path] = ("".join(lines[:function.lineno - 1]) + replacement + "".join(lines[function.end_lineno:])).encode()
    provider = files[provider_path].decode()
    old = 'enabled=os.getenv("ETF_LLM_ENABLED", "false").lower() == "true",'
    if provider.count(old) != 1:
        raise ValueError("旧模型开关实现与已核实版本不匹配，拒绝生成回退包")
    files[provider_path] = provider.replace(old, 'enabled=False,  # 新闻安全回退包禁止付费外呼').encode()
    for filename in (news_path, provider_path):
        compile(files[filename], filename, "exec")
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    files["release.json"] = json.dumps({
        **base, "id": args.output.stem, "version": "2.0-news-disabled",
        "base_release": base["id"], "patch_commit": commit,
        "news_mode": "COLLECTION_AND_PAID_ANALYSIS_DISABLED",
        "base_package_sha256": hashlib.sha256(args.base.read_bytes()).hexdigest(),
    }).encode()
    with zipfile.ZipFile(args.output, "w", zipfile.ZIP_DEFLATED) as output:
        for name, data in files.items():
            output.writestr(name, data)
    print(json.dumps({"release": args.output.stem, "base_release": base["id"],
                      "changed_entries": [news_path, provider_path, "release.json"],
                      "backend_sha256": hashlib.sha256(files["backend.jar"]).hexdigest(),
                      "sha256": hashlib.sha256(args.output.read_bytes()).hexdigest()}))


if __name__ == "__main__":
    main()
