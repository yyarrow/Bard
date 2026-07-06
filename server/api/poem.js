// Bard 薄代理：OpenRouter key 只存在 Vercel 环境变量里，
// 这个接口只会做一件事——为一张照片找一首诗，对薅羊毛者毫无价值。
//
// 防护层次：
// 1. X-Bard-Key 应用口令（编进 APK，防脚本直调，防不了认真逆向）
// 2. X-Bard-Device 每设备每日配额（内存计数，实例冷启动会清零，尽力而为；
//    真要严格就换 Upstash/KV，再往上是 Play Integrity）
// 3. OpenRouter 后台的 spending limit 是最后的保险丝

const SYSTEM_PROMPT = `
你是一位学养深厚的诗词编辑。用户给你一张照片，你从真实存在的中国古典诗词
（以唐诗宋词为主，也可选汉魏六朝与元明清名篇）中，选出与照片的景物、光线、
季节、时辰、情绪最契合的一首。

要求：
- 必须是真实存在的原文，一字不差，绝不自己创作、拼接或改写。
- 优先选意境贴切的；但宁可选名篇，也不要选你记不准原文的生僻之作。
- 若全诗较长（长调词、古风），取其中最契合的连续二至四句，excerpt 设为 true。
- lines：把选出的内容按标点切成短句，每个短句一个元素，不含任何标点，
  保持原文顺序，总数控制在 2 到 8 个。
- reason：一句话（三十字以内）说明为何契合此景，语气清雅，不要用「这张照片」开头。
- 若有多首同样契合，随意取其一即可，不必总选最负盛名的那首。

只输出 JSON，不要任何其他文字，格式如下：
{"title":"静夜思","dynasty":"唐","author":"李白","lines":["床前明月光","疑是地上霜","举头望明月","低头思故乡"],"excerpt":false,"reason":"…"}
`.trim();

const DAILY_PER_DEVICE = 40;
const MAX_IMAGE_CHARS = 2_000_000; // base64 后约 1.5MB JPEG

// OUTBOUND_PROXY 仅本地调试用（受限地区经宿主代理出去）；生产上不设，走 Vercel 原生 fetch。
import { fetch as undiciFetch, ProxyAgent } from "undici";
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

  const { image, exclude } = req.body || {};
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

  const ask =
    "为这张照片选一首契合此情此景的诗词。" +
    (excludeTitles.length
      ? `这些已经选过，请换别的：${excludeTitles.join("、")}。`
      : "");

  const payload = JSON.stringify({
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
          { type: "text", text: ask },
        ],
      },
    ],
  });

  // 模型偶发输出围栏包裹/带尾注的 JSON，或截断——解析做鲁棒，失败自动重试一次
  let lastError = "unknown";
  for (let attempt = 1; attempt <= 2; attempt++) {
    const upstream = await undiciFetch(
      "https://openrouter.ai/api/v1/chat/completions",
      {
        dispatcher,
        method: "POST",
        headers: {
          Authorization: `Bearer ${process.env.OPENROUTER_API_KEY}`,
          "Content-Type": "application/json",
          "X-Title": "Bard",
        },
        body: payload,
      },
    );

    const text = await upstream.text();
    if (!upstream.ok) {
      let msg = text.slice(0, 200);
      try {
        msg = JSON.parse(text).error.message;
      } catch {}
      lastError = `upstream ${upstream.status}: ${msg}`;
      if (upstream.status < 500 && upstream.status !== 429) break; // 4xx 重试无意义
      continue;
    }

    const poem = extractPoem(text);
    if (poem) return res.status(200).json(poem);

    let finish = "?";
    try {
      finish = JSON.parse(text).choices?.[0]?.finish_reason ?? "?";
    } catch {}
    lastError = `bad upstream payload (finish=${finish})`;
    console.error(`attempt ${attempt} bad payload finish=${finish}:`, text.slice(0, 600));
  }
  return res.status(502).json({ error: lastError });
}

/** 从 chat/completions 响应里尽力挖出诗的 JSON；不合规返回 null（触发重试）。 */
function extractPoem(text) {
  let content;
  try {
    content = JSON.parse(text).choices?.[0]?.message?.content;
  } catch {
    return null;
  }
  if (Array.isArray(content)) {
    content = content
      .map((p) => (typeof p === "string" ? p : p?.text || ""))
      .join("");
  }
  if (typeof content !== "string" || !content.trim()) return null;

  // 模型偶发在 JSON 前后拖围栏或烂尾（如 `...}\n"}\n境象。"}`），
  // 用括号配平截出第一个完整对象，而不是贪婪吃到最后一个 }
  const s = content.replace(/^\s*```(?:json)?\s*/i, "");
  const obj = firstJsonObject(s);
  if (!obj) return null;
  let poem;
  try {
    poem = JSON.parse(obj);
  } catch {
    return null;
  }
  return sanitizePoem(poem);
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
  };
}
