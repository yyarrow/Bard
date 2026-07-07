// 诗词语料库校验（严格模式）：模型报的诗必须能在库中核验，否则判编造。
//
// 三档处理（sim = 模型诗句与库中最佳匹配段的字符相似度）：
//   sim >= 0.75  确有其诗，保留模型文字（通行版往往比古籍刊本更符合课本记忆，
//                如《静夜思》全唐诗作"床前看月光"），作者/朝代以库为准
//   sim >= 0.45  模型记串了词，用库中原文段落替换
//   sim <  0.45  查无实据，判编造（触发上层重试）
//
// 语料 data/corpus.json 由 tools/build_corpus.py 生成：14 万首，
// [[title, author, dynasty, text], ...]，text 保留标点供切短句。

import fs from "fs";
import path from "path";

const PUNCT = /[\s，。、！？；：·—…,.!?;:'"“”‘’()（）《》〈〉【】\[\]]/g;
const CLAUSE_SEP = /[，。！？；、：]/;

let db = null;

function load() {
  if (db) return db;
  const raw = fs.readFileSync(path.join(process.cwd(), "data", "corpus.json"), "utf8");
  const poems = JSON.parse(raw);
  const byTitle = new Map(); // normTitle -> [idx...]
  const add = (key, i) => {
    if (!key) return;
    const list = byTitle.get(key);
    if (list) {
      if (list.length < 1000) list.push(i);
    } else {
      byTitle.set(key, [i]);
    }
  };
  for (let i = 0; i < poems.length; i++) {
    const t = poems[i][0];
    const full = norm(t);
    const base = baseTitle(t);
    add(full, i);
    if (base !== full) add(base, i);
  }
  db = { poems, byTitle, normTexts: null };
  return db;
}

function norm(s) {
  return String(s || "").replace(PUNCT, "");
}

/** 题目主干：去掉词题/序号，如「水调歌头·明月几时有」→「水调歌头」，「山园小梅二首 其一」→「山园小梅」 */
export function baseTitle(t) {
  let s = String(t || "").split(/[·・\s]/)[0].replace(PUNCT, "");
  for (let prev = ""; prev !== s; ) {
    prev = s;
    s = s.replace(/(其[一二三四五六七八九十]+|[一二三四五六七八九十百两0-9]+首|[0-9]+)$/, "");
  }
  return s;
}

/** 字符多重集重合率 ∈ [0,1] */
function charSim(a, b) {
  if (!a.length || !b.length) return 0;
  const freq = new Map();
  for (const c of a) freq.set(c, (freq.get(c) || 0) + 1);
  let common = 0;
  for (const c of b) {
    const n = freq.get(c) || 0;
    if (n > 0) {
      common++;
      freq.set(c, n - 1);
    }
  }
  return common / Math.max(a.length, b.length);
}

/** 在整首诗的短句序列里找与模型诗句最像的连续窗口 */
function bestWindow(clauses, modelJoined, windowSize) {
  if (clauses.length <= windowSize) {
    return { lines: clauses, sim: charSim(clauses.join(""), modelJoined), whole: true };
  }
  let best = { lines: [], sim: -1, whole: false };
  for (let i = 0; i + windowSize <= clauses.length; i++) {
    const w = clauses.slice(i, i + windowSize);
    const sim = charSim(w.join(""), modelJoined);
    if (sim > best.sim) best = { lines: w, sim, whole: false };
  }
  return best;
}

/**
 * 核验模型选的诗。命中返回修正后的 poem 及诊断信息，查无实据返回 null。
 * poem 需已过 sanitizePoem（lines 为纯汉字短句）。
 */
export function verifyPoem(poem) {
  const { poems, byTitle } = load();
  const modelJoined = poem.lines.join("");
  const windowSize = Math.min(Math.max(poem.lines.length, 2), 8);
  const authorNorm = norm(poem.author);

  const evaluate = (indices) => {
    let best = null;
    for (const i of indices) {
      const [title, author, dynasty, text] = poems[i];
      const clauses = text.split(CLAUSE_SEP).filter((c) => c.length > 0).map((c) => norm(c));
      const w = bestWindow(clauses, modelJoined, windowSize);
      // 同名不同篇很多（如 747 首水调歌头）：作者对得上加一点权重
      const score = w.sim + (authorNorm && norm(author).includes(authorNorm) ? 0.08 : 0);
      if (!best || score > best.score) {
        best = { i, w, score, sim: w.sim, title, author, dynasty, clauses };
      }
    }
    return best;
  };

  // 先按题目找；分数不够再按首句全库扫描兜底。注意撞名不能挡住扫描：
  // 模型报《凉州词》但实为别家同题诗时，只有扫句才找得到正主
  const keys = [...new Set([norm(poem.title), baseTitle(poem.title)])];
  const titleIdx = keys.flatMap((k) => byTitle.get(k) || []);
  let matchType = "title";
  let best = evaluate(titleIdx);

  if (!best || best.sim < 0.45) {
    const d = load();
    if (!d.normTexts) d.normTexts = d.poems.map((p) => norm(p[3]));
    const probe = poem.lines[0];
    const scanIdx = [];
    if (probe && probe.length >= 4) {
      const seen = new Set(titleIdx);
      for (let i = 0; i < d.normTexts.length && scanIdx.length < 50; i++) {
        if (!seen.has(i) && d.normTexts[i].includes(probe)) scanIdx.push(i);
      }
    }
    const scanBest = evaluate(scanIdx);
    if (scanBest && (!best || scanBest.score > best.score)) {
      best = scanBest;
      matchType = "line-scan";
    }
  }

  if (!best || best.sim < 0.45) return null;

  // 保留模型文字（通行版）还是回退库中原文：相似度高且逐句字数与原文
  // 完全一致才信模型——课本异文字数不变（床前看月光/床前明月光），
  // 而丢字错句必然打破字数（荷尽已无[擎]雨盖）
  const keepModelText =
    best.sim >= 0.75 &&
    poem.lines.length === best.w.lines.length &&
    poem.lines.every((l, i) => l.length === best.w.lines[i].length);
  const lines = keepModelText ? poem.lines : best.w.lines;
  return {
    poem: {
      // line-scan 命中意味着模型报的题目本身对不上，此时一律用库中正题
      title: keepModelText && matchType === "title"
        ? poem.title
        : best.title.replace(/\s+/g, " ").trim(),
      dynasty: best.dynasty,
      author: best.author,
      lines,
      excerpt: !best.w.whole || lines.length < best.clauses.length,
      reason: poem.reason,
    },
    matchType,
    sim: Math.round(best.sim * 100) / 100,
    keepModelText,
  };
}
