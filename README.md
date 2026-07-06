# Bard · 拾诗

拍一张照，为此情此景寻一句唐诗宋词，以「语文课本 / 线装书」的样式题在照片上。

## 玩法

1. 打开 app，对着眼前的景色按下快门（或从相册选图）。
2. Claude 端详照片，从真实存在的古典诗词里挑一首最契合的。
3. 诗以竖排楷体题在宣纸签条上，朱丝界栏、落款、朱印俱全。
4. 可以「换一首」、保存到相册、或分享。

## 配置 API Key

走 OpenRouter（默认模型 Gemini Flash），两种方式任选：

- 在 `local.properties` 里加一行 `BARD_API_KEY=sk-or-…`，重新编译；
- 或在 app 相机页右上角钥匙图标里粘贴（保存在本机 SharedPreferences）。

## 地区 / 代理注意

Gemini 在部分地区不可用（OpenRouter 会返回 `403 This model is not available in your region`）。
真机挂代理即可；**模拟器不走 macOS 系统代理**，需要手动指给宿主机的代理端口：

```bash
adb shell settings put global http_proxy 10.0.2.2:<你的代理端口>
# 取消：adb shell settings put global http_proxy :0
```

## 构建

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 技术栈

- Kotlin + Jetpack Compose（单 Activity，无导航库）
- CameraX 拍照，Photo Picker 选图
- OpenRouter chat/completions（Gemini Flash，图片走 base64 data URL，JSON 输出）
- 题字用 [霞鹜文楷 Lite](https://github.com/lxgw/LxgwWenKai-Lite)（OFL 许可）
- 排版渲染：`PoemPainter` 用 Canvas 手绘竖排、界栏与印章
