// 用语料库核验 daily 候选：过滤编造、取权威原文与作者朝代。
// 用法: node tools/verify_daily.mjs daily_candidates.json ../app/src/main/assets/daily.json
// （在 server/ 目录下运行，依赖 data/corpus.json）
import fs from "fs";
import { verifyPoem } from "../lib/corpus.js";

const [candPath, outPath] = process.argv.slice(2);
const { candidates } = JSON.parse(fs.readFileSync(candPath, "utf8"));

const out = [];
const seen = new Set();
let rejected = 0;
for (const c of candidates) {
  const months = (Array.isArray(c.months) ? c.months : [])
    .filter((m) => Number.isInteger(m) && m >= 1 && m <= 12);
  const lines = (Array.isArray(c.lines) ? c.lines : [])
    .filter((l) => typeof l === "string")
    .map((l) => l.replace(/[\s，。、！？；：·—…,.!?;:'"“”‘’()（）《》〈〉【】\[\]]/g, ""))
    .filter(Boolean);
  if (!c.title || !lines.length || !months.length) { rejected++; continue; }

  const v = verifyPoem({
    title: String(c.title), author: String(c.author || ""),
    dynasty: String(c.dynasty || ""), lines, reason: "",
  });
  if (!v) { rejected++; console.error(`  未核验: ${c.title}·${c.author}`); continue; }

  const key = v.poem.title + "·" + v.poem.author;
  if (seen.has(key)) { rejected++; continue; }
  seen.add(key);
  out.push({
    title: v.poem.title,
    dynasty: v.poem.dynasty,
    author: v.poem.author,
    lines: v.poem.lines,
    months,
  });
}

// 检查月份覆盖
const perMonth = Array.from({ length: 12 }, (_, i) =>
  out.filter((p) => p.months.includes(i + 1)).length);
console.log(`verified ${out.length}, rejected ${rejected}`);
console.log("per-month:", perMonth.join(","));
fs.writeFileSync(outPath, JSON.stringify(out, null, 0));
console.log(`-> ${outPath}`);
