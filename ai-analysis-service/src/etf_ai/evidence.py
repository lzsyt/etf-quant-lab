"""为已选入新闻生成确定性原文证据；不提取指标关系，不判断ETF情绪。"""

import hashlib
import json
import math
import re
from datetime import datetime
from zoneinfo import ZoneInfo

from etf_ai.intelligence import normalize
from etf_ai.news import safe_url
from etf_ai.relevance import INDEX_TERMS

EVIDENCE_VERSION = "news-evidence-v1"
MAX_EVIDENCE_PER_SOURCE = 4
MAX_CONTENT_CHARACTERS = 1600
_SENTENCE_END = re.compile(r"[。！？!?][”’」』]*")
_NUMBER = re.compile(r"[+−-]?\d+(?:[.．]\d+)?")


def _sha256(value):
    """哈希固定UTF-8原文或规范JSON，不纳入运行时刻、随机数和字典插入顺序。"""
    text = (
        value
        if isinstance(value, str)
        else json.dumps(
            value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        )
    )
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _aware(value):
    """即使调用者绕过Pydantic复制模型，证据入口也不能接受无时区时间。"""
    return isinstance(value, datetime) and value.utcoffset() is not None


def _source_identity(item, cutoff):
    """只接受真实可得且已完成长度控制的来源；原字段完整参与快照身份。"""
    safe_url(str(item.source_url))
    if not item.publication_verified:
        raise ValueError("证据来源发布时间未核验")
    if not _aware(item.published_at) or not _aware(item.fetched_at):
        raise ValueError("证据来源时间缺少时区")
    if item.published_at > cutoff or item.fetched_at > cutoff:
        raise ValueError("证据来源在输入截止时尚不可得")
    if item.nature not in {"REAL", "MOCK"}:
        raise ValueError("证据来源性质无效")
    if not re.fullmatch(r"[a-f0-9]{64}", item.content_sha256):
        raise ValueError("证据来源缺少有效内容哈希")
    content = item.metadata.get("content", "")
    if not isinstance(content, str) or len(content) > MAX_CONTENT_CHARACTERS:
        raise ValueError("证据必须基于已截断至1600字符的正文")
    content_type = item.metadata.get("content_type", "SUMMARY")
    if content_type not in {"SUMMARY", "EXCERPT", "FULL_TEXT"}:
        raise ValueError("证据来源内容类型无效")
    if not isinstance(item.title, str) or not isinstance(item.summary, str):
        raise TypeError("证据标题和摘要必须为原始文本")
    return {
        "source_id": item.id,
        "source_url": str(item.source_url),
        "source_name": item.source_name,
        "source_content_sha256": item.content_sha256,
        "source_original_content_hash": item.metadata.get("original_content_hash"),
        "source_content_type": content_type,
        "published_at": item.published_at.isoformat(),
        "fetched_at": item.fetched_at.isoformat(),
        "publication_precision": item.metadata.get("publication_precision")
        or (
            "DATE_CONSERVATIVE_END_OF_DAY"
            if item.source_name.endswith("DATE_PRECISION")
            else "SOURCE_TIMESTAMP"
        ),
        "publication_verified": item.publication_verified,
        "nature": item.nature,
        "content_truncated": bool(item.metadata.get("content_truncated", False)),
        "title": item.title,
        "summary": item.summary,
        "content": content,
    }


def _context_table(text):
    """数字密集或表格排列仅作连续上下文，不推断扁平化后的行列和指标对应关系。"""
    numbers = _NUMBER.findall(text)
    if len(numbers) < 8:
        return False
    explicit_table = bool(re.search(r"表\s*\d|单位\s*[：:]|绝对额", text))
    dense = sum(character.isdigit() for character in text) / max(1, len(text)) >= 0.18
    columns = bool(re.search(r"(?:[+−-]?\d+(?:\.\d+)?\s+){3,}", text))
    return explicit_table or dense or columns


def _spans(text, limit):
    """按完整中文句边界分组；无法独立阅读的表格或残句保留原片段并明确标记。"""
    if not text.strip() or limit <= 0:
        return [], None
    if _context_table(text):
        return [(0, len(text), False, "TABULAR_OR_NUMERIC_DENSE")], None
    ends = [match.end() for match in _SENTENCE_END.finditer(text)]
    if not ends:
        return [(0, len(text), False, "INCOMPLETE_OR_UNSEGMENTED_TEXT")], None
    # 尾部没有句末符号时不能冒称完整证据；有完整句时优先完整句，并记录被舍弃范围。
    complete_end = ends[-1]
    omitted = None
    if text[complete_end:].strip():
        omitted = {
            "start": complete_end,
            "end": len(text),
            "reason": "INCOMPLETE_TRAILING_FRAGMENT",
        }
    spans = []
    start = 0
    while ends and len(spans) < limit:
        slots = min(limit - len(spans), len(ends))
        target = start + math.ceil((complete_end - start) / slots)
        eligible = ends[: len(ends) - slots + 1]
        end = min(eligible, key=lambda position: (abs(position - target), position))
        spans.append((start, end, True, None))
        start = end
        ends = [position for position in ends if position > end]
    return spans, omitted


def build_evidence(request):
    """构造同北京日稳定的不可变证据目录；任何最终来源或字段变化都会生成新快照。

    调用者先完成ETF筛选及1600字符正文截断，每次预算删文后重新调用本函数。
    start/end使用Python Unicode字符的左闭右开区间，始终满足原字段[start:end]==text。
    standalone只说明片段可独立阅读，不表示新闻足以支持h1、有效情绪或融合交易。
    """
    if not _aware(request.cutoff):
        raise ValueError("证据截止时间缺少时区")
    if getattr(request, "horizon", 1) != 1:
        raise ValueError("证据仅支持h1")
    if (
        not request.symbols
        or len(set(request.symbols)) != len(request.symbols)
        or not set(request.symbols) <= INDEX_TERMS.keys()
    ):
        raise ValueError("证据ETF集合无效")
    if len(request.news) > 15 or len({item.id for item in request.news}) != len(
        request.news
    ):
        raise ValueError("证据来源数量超限或ID重复")
    sources = [_source_identity(item, request.cutoff) for item in request.news]
    snapshot_id = _sha256(
        {
            "evidence_version": EVIDENCE_VERSION,
            "decision_day": request.cutoff.astimezone(ZoneInfo("Asia/Shanghai"))
            .date()
            .isoformat(),
            "horizon": 1,
            "symbols": sorted(request.symbols),
            "sources": sorted(sources, key=lambda source: source["source_id"]),
        }
    )
    evidence, diagnostics = [], []
    for source in sources:
        source_id = source["source_id"]
        candidates = []
        if source["title"].strip():
            candidates.append(("title", 0, len(source["title"]), True, None))
        summary = source["summary"]
        duplicate_summary = bool(normalize(summary)) and normalize(
            source["content"]
        ).startswith(normalize(summary))
        fields = []
        if duplicate_summary:
            diagnostics.append(
                {
                    "source_id": source_id,
                    "field": "summary",
                    "reason": "DUPLICATE_OF_CONTENT_PREFIX",
                }
            )
        elif summary.strip():
            fields.append("summary")
        if source["content"].strip():
            fields.append("content")
        for index, field in enumerate(fields):
            # 给后续字段至少留一个位置；独立摘要只占一条，正文使用剩余位置。
            slots = (
                MAX_EVIDENCE_PER_SOURCE - len(candidates) - (len(fields) - index - 1)
            )
            if field == "summary" and "content" in fields:
                slots = min(slots, 1)
            spans, omitted = _spans(source[field], slots)
            candidates.extend(
                (field, start, end, standalone, reason)
                for start, end, standalone, reason in spans
            )
            if omitted:
                diagnostics.append({"source_id": source_id, "field": field, **omitted})
        seen = set()
        for field, start, end, standalone, reason in candidates:
            text = source[field][start:end]
            normalized = normalize(text)
            if not normalized or normalized in seen:
                diagnostics.append(
                    {
                        "source_id": source_id,
                        "field": field,
                        "start": start,
                        "end": end,
                        "reason": "DUPLICATE_EVIDENCE_TEXT",
                    }
                )
                continue
            seen.add(normalized)
            text_sha256 = _sha256(text)
            identity = {
                "snapshot_id": snapshot_id,
                "source_id": source_id,
                "field": field,
                "start": start,
                "end": end,
                "text_sha256": text_sha256,
            }
            entry = {
                "id": "EV_" + _sha256(identity),
                "source_id": source_id,
                "field": field,
                "start": start,
                "end": end,
                "text": text,
                "content_type": "EXCERPT" if field == "content" else "SUMMARY",
                "text_sha256": text_sha256,
                "source_content_sha256": source["source_content_sha256"],
                "source_content_type": source["source_content_type"],
                "published_at": source["published_at"],
                "fetched_at": source["fetched_at"],
                "standalone": standalone,
                "context_required": not standalone,
            }
            if source["source_original_content_hash"]:
                entry["source_original_content_hash"] = source[
                    "source_original_content_hash"
                ]
            if reason:
                entry["context_reason"] = reason
            # 断言只检查我们刚生成的切片，不修复或规范化原文。
            assert source[field][entry["start"] : entry["end"]] == entry["text"]
            evidence.append(entry)
    return {
        "snapshot_id": snapshot_id,
        "evidence_version": EVIDENCE_VERSION,
        "evidence": evidence,
        "diagnostics": diagnostics,
    }
