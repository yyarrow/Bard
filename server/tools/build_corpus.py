#!/usr/bin/env python3
"""
从 chinese-poetry 数据集构建拾诗的紧凑语料库。

用法: python3 build_corpus.py <chinese-poetry目录> <输出corpus.json>

输出格式: [[title, author, dynasty, text], ...]（text 保留原标点，供切短句）
- 全唐诗:全量,繁转简
- 全宋诗:仅名家白名单(25万全量太大,模型也只会选名家)
- 宋词/诗经/楚辞/曹操/元曲/纳兰:全量
"""
import json
import glob
import re
import sys

from opencc import OpenCC

t2s = OpenCC("t2s").convert

# 宋诗名家白名单（简体）
SONG_AUTHORS = set("""
苏轼 陆游 王安石 杨万里 范成大 朱熹 黄庭坚 梅尧臣 欧阳修 曾巩 秦观
陈师道 陈与义 林逋 晏殊 司马光 王令 张耒 晁补之 贺铸 周邦彦 文天祥
辛弃疾 李清照 岳飞 朱淑真 叶绍翁 翁卷 赵师秀 徐玑 姜夔 刘克庄 戴复古
谢枋得 郑思肖 林升 卢梅坡 苏洵 苏辙 曾几 程颢 程颐 邵雍 张栻 吕本中
汪藻 王禹偁 寇准 柳永 晏几道 张先 宋祁 唐庚 僧志南 雷震 王观 乐雷发
""".split())

WS = re.compile(r"\s+")


# opencc t2s 不处理异体字/古字（輭/軟、氊/氈…）。从 Unihan 异体字关系自动
# 生成归一化表，《通用规范汉字表》做安全闸：只把表外生僻字映射到表内通行字，
# 通行字之间绝不互转（避免 呵→啊 之类误伤）。
TOOLS_DATA = __file__.rsplit("/", 1)[0] + "/data"

# 人工确认的映射，优先级最高（按埋点观测持续补充；
# 古今义有分歧的如 拚/敧/秪 刻意不映射）
MANUAL_VARIANTS = {
    "輭": "软", "氊": "毡", "牀": "床", "邨": "村", "隄": "堤",
    "馀": "余", "鬰": "郁", "嬾": "懒", "髪": "发", "浄": "净",
    "鶑": "莺", "歛": "敛", "垅": "垄", "珮": "佩",
}

VARIANT_FIELDS = ("kSimplifiedVariant", "kZVariant", "kSemanticVariant")


def build_variant_table():
    std = set(open(f"{TOOLS_DATA}/gsc.txt").read()) - {"\n"}
    pairs = {}
    for line in open(f"{TOOLS_DATA}/Unihan_Variants.txt"):
        if line.startswith("#") or "\t" not in line:
            continue
        cp, field, val = line.rstrip("\n").split("\t")[:3]
        if field not in VARIANT_FIELDS:
            continue
        src = chr(int(cp[2:], 16))
        tgts = [chr(int(v.split("<")[0][2:], 16)) for v in val.split()]
        pairs.setdefault(src, {}).setdefault(field, []).extend(tgts)

    mapping = {}
    for src, fields in pairs.items():
        if src in std or t2s(src) in std:
            continue  # 本身（或繁转简后）就是通行字，不动
        for field in VARIANT_FIELDS:  # 简化字关系 > 同字异形 > 同义异体
            tgt = next((t2s(t) for t in fields.get(field, []) if t2s(t) in std), None)
            if tgt and tgt != src:
                mapping[src] = tgt
                break
    mapping.update(MANUAL_VARIANTS)
    print(f"variant table: {len(mapping)} chars")
    return str.maketrans(mapping)


VARIANTS = build_variant_table()

PUNCT_END = tuple("，。！？；、：")


def clean_text(paragraphs):
    # 部分集子（如纳兰词）段落无尾标点，补句号避免拼接后短句粘连
    parts = []
    for p in paragraphs:
        p = WS.sub("", p)
        if not p:
            continue
        if not p.endswith(PUNCT_END):
            p += "。"
        parts.append(p)
    return "".join(parts)


def main(root, out_path):
    poems = []
    seen = set()

    def add(title, author, dynasty, paragraphs, convert=False):
        if not title or not paragraphs:
            return
        text = clean_text(paragraphs)
        if convert:
            title, author, text = t2s(title), t2s(author), t2s(text)
        title = title.translate(VARIANTS)
        text = text.translate(VARIANTS)
        if len(text) < 8 or len(text) > 2000:
            return
        key = (title, author, text[:24])
        if key in seen:
            return
        seen.add(key)
        poems.append([title, author or "佚名", dynasty, text])

    for f in sorted(glob.glob(f"{root}/全唐诗/poet.tang.*.json")):
        for p in json.load(open(f)):
            add(p.get("title", ""), p.get("author", ""), "唐", p.get("paragraphs", []), convert=True)

    for f in sorted(glob.glob(f"{root}/全唐诗/poet.song.*.json")):
        for p in json.load(open(f)):
            author = t2s(p.get("author", ""))
            if author in SONG_AUTHORS:
                add(p.get("title", ""), p.get("author", ""), "宋", p.get("paragraphs", []), convert=True)

    for f in sorted(glob.glob(f"{root}/宋词/ci.song.*.json")):
        for p in json.load(open(f)):
            add(p.get("rhythmic", "") or p.get("title", ""), p.get("author", ""), "宋", p.get("paragraphs", []))

    for p in json.load(open(f"{root}/诗经/shijing.json")):
        add(p.get("title", ""), "佚名", "先秦", p.get("content", []))

    for f in glob.glob(f"{root}/楚辞/chuci.json"):
        for p in json.load(open(f)):
            add(p.get("title", ""), p.get("author", ""), "先秦", p.get("content", []))

    for p in json.load(open(f"{root}/曹操诗集/caocao.json")):
        add(p.get("title", ""), "曹操", "汉", p.get("paragraphs", []))

    for p in json.load(open(f"{root}/元曲/yuanqu.json")):
        add(p.get("title", ""), p.get("author", ""), "元", p.get("paragraphs", []))

    for f in glob.glob(f"{root}/纳兰性德/*.json"):
        for p in json.load(open(f)):
            add(p.get("title", ""), p.get("author", "纳兰性德"), "清", p.get("para", []))

    with open(out_path, "w") as fh:
        json.dump(poems, fh, ensure_ascii=False, separators=(",", ":"))
    print(f"{len(poems)} poems -> {out_path}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
