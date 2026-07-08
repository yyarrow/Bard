# Bard（拾诗）— 拍照配诗手账安卓 app

拍照 → LLM 选一首契合的诗 → 楷体题在照片上（带日期节气题款）→ 入册成手账。0.2.0 起定位「诗意手账」：本地手账时间轴（journal/Journal.kt,文件存储 filesDir/journal/）、今日一诗（assets/daily.json,113 首离线精选,server/tools/make_daily.py+verify_daily.mjs 生成）、streak、图鉴（诗人/朝代/节气）、农历节气（util/Almanac.kt,寿星公式）。Kotlin + Compose 单模块,LLM 走 OpenRouter + gemini-3.5-flash(用户指定,不用 Claude)。无测试设施。

## 构建 & 安装
```bash
./gradlew :app:assembleDebug          # 或 assembleRelease / installDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
- 已发布 APK:`shishi-0.2.0.apk`(release,debug keystore 签名)。
- 配置注入:`local.properties` 的 `BARD_API_BASE`(默认 https://bard-api.warmbeing.com)和 `BARD_APP_SECRET` → BuildConfig;用户在设置里填个人 OpenRouter key 则绕过代理直连(ApiKeyStore)。

## 调试技巧
- **Mock 模式不耗 API**:`adb shell am start -n app.bard/.MainActivity --ez mock true`(固定返回《山居秋暝》)。
- 模拟器 AVD:Paperboy_Pixel_8_API_35,后置摄像头 virtualscene;装完 app 第一次 `input tap` 快门常太早,需再点一次。
- **代理坑**(Gemini 受限地区,宿主代理 127.0.0.1:12334):走后端代理模式时模拟器要**清掉**全局代理 `adb shell settings put global http_proxy :0`(否则宿主代理解析不了 10.0.2.2);只有旧直连模式才需要 `settings put global http_proxy 10.0.2.2:12334`。

## 后端 server/(Vercel 项目 bard-api)
- `api/poem.js` 薄代理:校验 `X-Bard-Key` 应用口令,`X-Bard-Device`(ANDROID_ID)每日 40 次内存限额(尽力而为);OpenRouter key 在 Vercel env。
- **严格核验(lib/corpus.js)**:模型选的诗必须在本地语料库(14万首,data/corpus.json,34MB,gitignored)核验;sim≥0.75 且逐句字数一致保留模型通行版文字,0.45–0.75 用库中原文替换,再低判编造带黑名单重试(共3次)。语料重建:`python3 tools/build_corpus.py <chinese-poetry仓库> data/corpus.json`(需 pip 装 opencc-python-reimplemented;坑:全唐诗《静夜思》是宋刊本"床前看月光",不能无脑用库文替换)。
- **埋点**:每请求一行结构化 console.log(evt=poem,含 match/sim/attempt/rejected) + 落 Vercel Blob 私有存储 bard-events(`events/<日期>/*.json`);查看:`vercel blob list --rw-token <BLOB_READ_WRITE_TOKEN(.env.local里)>`。
- 本地调试:`cd server && vercel dev --listen 3000`,`.env` 里可设 `OUTBOUND_PROXY=http://127.0.0.1:12334`(生产不设)。注意 vercel dev 每请求新起进程,内存限流本地测不出来。
- 生产:https://bard-api.warmbeing.com/api/poem(warmbeing.com 在 Spaceship,CNAME → vercel;bard-api-seven.vercel.app 是备用,带 scope 的别名会被 Vercel SSO 拦)。
- env:`BARD_APP_SECRET` / `OPENROUTER_API_KEY` / 可选 `OUTBOUND_PROXY`,无 .env.example。

## 代码结构(app/src/main/java/app/bard/)
`MainActivity.kt` · `poem/`(Poem 模型、PoemFinder 请求后端、ApiKeyStore)· `ui/`(BardApp/Camera/Loading/ResultScreen)· `render/PoemPainter.kt`(Canvas 题字:2x 超采样、竖排/横排、捏合缩放拖动、长按看出处,无底板无印章)· `util/Images.kt`;字体 `res/font/lxgw.ttf`(霞鹜文楷 Lite)。
