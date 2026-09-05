#!/usr/bin/env python3
"""
生成「今日一诗」候选清单：请 LLM 按月份出名篇，产出 daily_candidates.json，
再由 verify_daily.mjs 用语料库核验并取权威原文。
用法: OPENROUTER_API_KEY=... python3 make_daily.py [输出文件]
"""
import json
import os
import sys
import urllib.request

PROMPT = """请列出 50 首适合在{months}月份做"每日一诗"的中国古典诗词名篇
（唐诗、宋诗、宋词为主，可少量选诗经、楚辞、汉魏六朝诗、元曲、纳兰词）。要求：

- 全部是真实存在、广为流传的作品，你能一字不差背出原文。
- 每首标注适合展示的月份（在 {months} 范围内，考虑季节、节气、节日意象）。
- lines 给出最适合单独展示的 2-6 个短句（短诗给全诗），不含标点，保持原文顺序。

只输出 JSON：
{{"candidates":[{{"title":"山居秋暝","author":"王维","dynasty":"唐","months":[8,9,10],"lines":["空山新雨后","天气晚来秋","明月松间照","清泉石上流"]}},...]}}
"""


def parse_relaxed(content: str) -> dict:
    """截断的 JSON 修复：退到最后一个完整的候选对象再收口。"""
    start = content.find("{")
    try:
        return json.loads(content[start:content.rfind("}") + 1])
    except json.JSONDecodeError:
        cut = content.rfind("},")
        if cut < 0:
            raise
        return json.loads(content[start:cut + 1] + "]}")

def main():
    key = os.environ.get("OPENROUTER_API_KEY", "")
    if not key:
        sys.exit("需要 OPENROUTER_API_KEY 环境变量")
    out_path = sys.argv[1] if len(sys.argv) > 1 else "daily_candidates.json"

    proxy = os.environ.get("OUTBOUND_PROXY")
    opener = (
        urllib.request.build_opener(
            urllib.request.ProxyHandler({"https": proxy, "http": proxy}))
        if proxy else urllib.request.build_opener()
    )

    all_candidates = []
    for months in ("1、2、3、4", "5、6、7、8", "9、10、11、12"):
        body = json.dumps({
            "model": "google/gemini-3.8-flash",
            "max_tokens": 16000,
            "temperature": 0.7,
            "response_format": {"type": "json_object"},
            "messages": [{"role": "user", "content": PROMPT.format(months=months)}],
        }).encode()
        req = urllib.request.Request(
            "https://openrouter.ai/api/v1/chat/completions",
            data=body,
            headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
        )
        resp = json.loads(opener.open(req, timeout=300).read())
        content = resp["choices"][0]["message"]["content"]
        batch = parse_relaxed(content).get("candidates", [])
        print(f"months {months}: {len(batch)}")
        all_candidates += batch

    with open(out_path, "w") as f:
        json.dump({"candidates": all_candidates}, f, ensure_ascii=False, indent=1)
    print(f"{len(all_candidates)} candidates -> {out_path}")


if __name__ == "__main__":
    main()
