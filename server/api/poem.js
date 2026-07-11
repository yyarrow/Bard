// Bard 薄代理：OpenRouter key 只存在 Vercel 环境变量里，
// 这个接口只会做一件事——为一张照片找一首诗，对薅羊毛者毫无价值。
//
// 防护层次：
// 1. X-Bard-Key 应用口令（编进 APK，防脚本直调，防不了认真逆向）
// 2. X-Bard-Device 每设备每日配额（内存计数，实例冷启动会清零，尽力而为；
//    真要严格就换 Upstash/KV，再往上是 Play Integrity）
// 3. OpenRouter 后台的 spending limit 是最后的保险丝

const SYSTEM_PROMPT = `
你是一位学养深厚的诗词编辑。用户给你一张照片，你从真实存在的中国古典诗词中，
选出与照片的景物、光线、季节、时辰、情绪最契合的一首。
选择范围：唐诗、宋诗、宋词为主，也可选诗经、楚辞、汉魏六朝诗、元曲、纳兰词；
其余明清作品不要选。

要求：
- 必须是真实存在的原文，一字不差，绝不自己创作、拼接或改写。
- 一切以意境贴合照片为先；名篇与生僻之作一视同仁，只要你记得准原文。
- 若全诗较长（长调词、古风），取其中最契合的连续二至四句，excerpt 设为 true。
- lines：把选出的内容按标点切成短句，每个短句一个元素，不含任何标点，
  保持原文顺序，总数控制在 2 到 8 个。
- reason：一句话（三十字以内）说明为何契合此景，语气清雅，不要用「这张照片」开头。
- 若有多首同样契合，随意取其一即可，不必总选最负盛名的那首。
- 只选你能一字不差背出原文的作品；记不准的宁可不选。

给出三首互不相同的备选（题目不能相同），按契合度从高到低排列；
若用户消息另行指定了备选数量或心境要求，以用户消息为准。
只输出 JSON，不要任何其他文字，格式如下：
{"candidates":[{"title":"静夜思","dynasty":"唐","author":"李白","lines":["床前明月光","疑是地上霜","举头望明月","低头思故乡"],"excerpt":false,"reason":"…"},…]}
`.trim();

const DAILY_PER_DEVICE = 40;
const MAX_IMAGE_CHARS = 2_000_000; // base64 后约 1.5MB JPEG
const MOODS = ["豪放", "婉约", "闲适", "禅意", "思乡", "怅惘", "欢喜"];

// OUTBOUND_PROXY 仅本地调试用（受限地区经宿主代理出去）；生产上不设，走 Vercel 原生 fetch。
import { createHash } from "node:crypto";
import { fetch as undiciFetch, ProxyAgent } from "undici";
import { baseTitle, verifyPoem } from "../lib/corpus.js";
const dispatcher = process.env.OUTBOUND_PROXY
  ? new ProxyAgent(process.env.OUTBOUND_PROXY)
  : undefined;

const usage = new Map();

export default async function handler(req, res) {
  if (req.method !== "POST") {
    return res.status(405).json({ error: "POST only" });
  }
  if ((req.headers["x-bard-key"] || "") !== process.env.BARD_APP_SECRET) {
    return res.status(401).json({ error: "unauthorized" });
  }

  const device = String(req.headers["x-bard-device"] || "").slice(0, 64);
  if (!device) {
    return res.status(400).json({ error: "missing device id" });
  }

  const day = new Date().toISOString().slice(0, 10);
  const key = `${day}:${device}`;
  const count = (usage.get(key) || 0) + 1;
  if (usage.size > 20_000) usage.clear();
  usage.set(key, count);
  if (count > DAILY_PER_DEVICE) {
    return res.status(429).json({ error: "daily quota exceeded" });
  }

  const { image, exclude, want } = req.body || {};
  if (
    typeof image !== "string" ||
    !image.startsWith("data:image/jpeg;base64,") ||
    image.length > MAX_IMAGE_CHARS
  ) {
    return res.status(400).json({ error: "bad image" });
  }
  const excludeTitles = Array.isArray(exclude)
    ? exclude.filter((t) => typeof t === "string").map((t) => t.slice(0, 40)).slice(0, 20)
    : [];
  // want=1: 常规选诗（模型仍出 3 候选，验过的都随 alternates 带回）
  // want>=4: 批量补货，心境各异并带 mood 标签（客户端缓存给换一首/心情切换用）
  let wanted = Math.min(Math.max(Number.isInteger(want) ? want : 1, 1), 10);
  const batch = wanted >= 4;
  // 心境要求彼此不同，数量不能超过心境种数，否则约束不可满足
  if (batch) wanted = Math.min(wanted, MOODS.length);

  const ask = (batch
    ? `为这张照片挑选 ${wanted} 首各自契合、心境尽量彼此不同的诗词候选（题目不能相同），` +
      `每首在 JSON 里额外加 "mood" 字段，取值只能是：${MOODS.join("、")}。`
    : "为这张照片选一首契合此情此景的诗词。") +
    (excludeTitles.length
      ? `这些已经选过，请换别的：${excludeTitles.join("、")}。`
      : "");

  // 严格模式：模型选的诗必须能在本地语料库核验，编造/记错就带着黑名单重试；
  // 「换一首」的排除名单按题目主干硬校验（模型光靠提示词管不住，见 凉州词 案例）
  const t0 = Date.now();
  const dev = deviceHash(device);
  const excludeSet = new Set(
    excludeTitles.map((t) => baseTitle(t)).filter(Boolean),
  );
  const rejected = [];
  const duplicated = [];
  // 跨轮累积已核验候选：批量模式一轮没凑满时，第二轮补足而不是拿零头交差
  const passed = [];
  const passedTitles = new Set();
  let attemptsUsed = 0;
  let lastError = "unknown";
  // 函数上限 60s（vercel.json）：每轮上游请求限时 25s；重试只在时间预算还够时发起，
  // 否则宁可交出已凑到的部分结果，也不能整个调用被平台掐掉、颗粒无收
  const ATTEMPT_TIMEOUT_MS = 25_000;
  const RETRY_BUDGET_MS = 30_000;
  for (let attempt = 1; attempt <= 2; attempt++) {
    if (attempt > 1 && Date.now() - t0 > RETRY_BUDGET_MS) break;
    attemptsUsed = attempt;
    const hints = [];
    if (rejected.length) {
      hints.push(
        `${rejected.map((r) => `《${r}》`).join("、")}未能通过诗词库原文核验，多半记错或不存在，请换一首你能一字不差背出原文的作品。`,
      );
    }
    if (duplicated.length) {
      hints.push(
        `${duplicated.map((r) => `《${r}》`).join("、")}与已选过的重复，必须换一首完全不同的作品。`,
      );
    }
    const hint = hints.length ? `注意：${hints.join("")}仍以贴合照片意境为先。` : "";
    let upstream;
    let text;
    try {
      upstream = await undiciFetch(
        "https://openrouter.ai/api/v1/chat/completions",
        {
          dispatcher,
          signal: AbortSignal.timeout(ATTEMPT_TIMEOUT_MS),
          method: "POST",
          headers: {
            Authorization: `Bearer ${process.env.OPENROUTER_API_KEY}`,
            "Content-Type": "application/json",
            "X-Title": "Bard",
          },
          body: JSON.stringify({
            model: "google/gemini-3.5-flash",
            max_tokens: 4000,
            temperature: 1.0,
            response_format: { type: "json_object" },
            messages: [
              { role: "system", content: SYSTEM_PROMPT },
              {
                role: "user",
                content: [
                  { type: "image_url", image_url: { url: image } },
                  { type: "text", text: ask + hint },
                ],
              },
            ],
          }),
        },
      );
      text = await upstream.text();
    } catch (err) {
      lastError = `upstream fetch failed: ${err?.name === "TimeoutError" ? "timeout" : err?.message}`;
      continue;
    }
    if (!upstream.ok) {
      let msg = text.slice(0, 200);
      try {
        msg = JSON.parse(text).error.message;
      } catch {}
      lastError = `upstream ${upstream.status}: ${msg}`;
      if (upstream.status < 500 && upstream.status !== 429) break; // 4xx 重试无意义
      continue;
    }

    const candidates = extractCandidates(text);
    if (!candidates.length) {
      let finish = "?";
      try {
        finish = JSON.parse(text).choices?.[0]?.finish_reason ?? "?";
      } catch {}
      lastError = `bad upstream payload (finish=${finish})`;
      console.error(`attempt ${attempt} bad payload finish=${finish}:`, text.slice(0, 600));
      continue;
    }

    // 逐个核验+排重，通过的全部收下（首位是主选，其余作为备胎随响应带回）
    for (let ci = 0; ci < candidates.length; ci++) {
      const poem = candidates[ci];
      const verified = verifyPoem(poem);
      if (!verified) {
        rejected.push(`${poem.title}·${poem.author}`);
        lastError = "这次选的诗未能核验原文，请再试一次";
        continue;
      }
      const tKey = baseTitle(verified.poem.title);
      if (
        excludeSet.has(tKey) || excludeSet.has(baseTitle(poem.title)) ||
        passedTitles.has(tKey)
      ) {
        duplicated.push(verified.poem.title);
        lastError = "换来换去还是这首，请再试一次";
        continue;
      }
      passedTitles.add(tKey);
      passed.push({ ...verified.poem, mood: poem.mood || "", _cand: ci, _v: verified });
    }

    // 批量模式凑满 wanted 才提前收工，没凑满就再打一轮；常规模式有一首即可
    if (passed.length >= (batch ? wanted : 1)) break;
  }

  if (passed.length > 0) {
    const first = passed[0];
    await logEvent({
      ok: true, dev, attempt: attemptsUsed, cand: first._cand, ms: Date.now() - t0,
      want: wanted, got: passed.length,
      match: first._v.matchType, sim: first._v.sim, keep: first._v.keepModelText,
      title: first.title, author: first.author,
      rejected: rejected.length ? rejected : undefined,
      duplicated: duplicated.length ? duplicated : undefined,
    });
    const strip = ({ _cand, _v, ...p }) => p;
    return res.status(200).json({
      ...strip(first),
      alternates: passed.slice(1).map(strip),
    });
  }

  await logEvent({
    ok: false, dev, ms: Date.now() - t0, err: lastError,
    rejected: rejected.length ? rejected : undefined,
    duplicated: duplicated.length ? duplicated : undefined,
  });
  return res.status(502).json({ error: lastError });
}

function deviceHash(device) {
  return createHash("sha256").update(device).digest("hex").slice(0, 12);
}

/** 埋点：结构化日志一行 + （配了 Blob 时）落一个事件文件做长期留存。 */
async function logEvent(e) {
  const event = { evt: "poem", ts: new Date().toISOString(), ...e };
  const line = JSON.stringify(event);
  console.log(line);
  if (process.env.BLOB_READ_WRITE_TOKEN) {
    try {
      const { put } = await import("@vercel/blob");
      await put(
        `events/${event.ts.slice(0, 10)}/${Date.now()}-${Math.random().toString(36).slice(2, 8)}.json`,
        line,
        { access: "private", contentType: "application/json" },
      );
    } catch (err) {
      console.error("blob log failed:", err?.message);
    }
  }
}

/** 从 chat/completions 响应里挖出候选诗列表；不合规的候选剔除。 */
function extractCandidates(text) {
  let content;
  try {
    content = JSON.parse(text).choices?.[0]?.message?.content;
  } catch {
    return [];
  }
  if (Array.isArray(content)) {
    content = content
      .map((p) => (typeof p === "string" ? p : p?.text || ""))
      .join("");
  }
  if (typeof content !== "string" || !content.trim()) return [];

  // 模型偶发在 JSON 前后拖围栏或烂尾（如 `...}\n"}\n境象。"}`），
  // 用括号配平截出第一个完整对象，而不是贪婪吃到最后一个 }
  const s = content.replace(/^\s*```(?:json)?\s*/i, "");
  const obj = firstJsonObject(s);
  if (!obj) return [];
  let parsed;
  try {
    parsed = JSON.parse(obj);
  } catch {
    return [];
  }
  const list = Array.isArray(parsed?.candidates) ? parsed.candidates : [parsed];
  return list.map(sanitizePoem).filter(Boolean);
}

function firstJsonObject(s) {
  const start = s.indexOf("{");
  if (start < 0) return null;
  let depth = 0;
  let inStr = false;
  let esc = false;
  for (let i = start; i < s.length; i++) {
    const c = s[i];
    if (inStr) {
      if (esc) esc = false;
      else if (c === "\\") esc = true;
      else if (c === '"') inStr = false;
    } else if (c === '"') inStr = true;
    else if (c === "{") depth++;
    else if (c === "}") {
      depth--;
      if (depth === 0) return s.slice(start, i + 1);
    }
  }
  return null;
}

const PUNCT = /[\s，。、！？；：·—…,.!?;:'"“”‘’()（）《》〈〉【】\[\]]/g;
const CJK_ONLY = /^[〇一-鿿㐀-䶿]+$/;

/** 清洗并校验：诗句必须是纯汉字（模型抽风会混入英文/胡话），不合规判废。 */
function sanitizePoem(poem) {
  if (!poem || typeof poem.title !== "string" || !Array.isArray(poem.lines)) {
    return null;
  }
  const lines = poem.lines
    .filter((l) => typeof l === "string")
    .map((l) => l.replace(PUNCT, ""))
    .filter((l) => l.length > 0);
  if (lines.length < 1 || lines.length > 10) return null;
  if (!lines.every((l) => l.length >= 2 && l.length <= 16 && CJK_ONLY.test(l))) {
    return null;
  }
  return {
    title: poem.title.slice(0, 40),
    dynasty: typeof poem.dynasty === "string" ? poem.dynasty.slice(0, 8) : "",
    author: typeof poem.author === "string" ? poem.author.slice(0, 16) : "",
    lines,
    excerpt: poem.excerpt === true,
    reason: typeof poem.reason === "string" ? poem.reason.slice(0, 80) : "",
    mood: MOODS.includes(poem.mood) ? poem.mood : "",
  };
}
