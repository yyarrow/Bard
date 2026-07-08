package app.bard.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import app.bard.poem.Poem
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 把诗句直接题在照片上，竖排（从右往左逐句）或横排（逐行居中）。
 * 不落题目与款识（出处在界面里长按查看），无印章、无底板，
 * 靠柔和阴影与自动选色（墨/纸白）保证可读。
 * 覆盖层按 2x 超采样渲染，用户放大后依然锐利。
 */
object PoemPainter {

    private val PUNCT =
        Regex("""[\s，。、！？；：·—…,.!?;:'"“”‘’()（）《》〈〉【】\[\]]""")

    private const val ADVANCE = 1.16f   // 竖排字距（相对字号）
    private const val COL_W = 1.58f     // 竖排列宽（相对字号）
    private const val H_ADV = 1.08f     // 横排字距（相对字号）
    private const val LINE_H = 1.62f    // 横排行高（相对字号）
    private const val RES = 2f          // 超采样倍率

    private const val INK_DARK = 0xFF2B2115.toInt()   // 墨色
    private const val INK_LIGHT = 0xFFF6EFDE.toInt()  // 纸白

    class Layout(
        val lines: List<String>,
        val horizontal: Boolean,
        val charSize: Float,
        val pad: Float,
        val width: Int,
        val height: Int,
    )

    fun measure(photoW: Int, photoH: Int, poem: Poem, horizontal: Boolean): Layout {
        val fallback = PUNCT.replace(poem.title.substringBefore('·'), "").take(12)
            .ifEmpty { "无题" }
        val lines = poem.lines.map { PUNCT.replace(it, "") }
            .filter { it.isNotEmpty() }
            .take(10)
            .ifEmpty { listOf(fallback) }

        val maxChars = lines.maxOf { it.length }
        val charSize: Float
        val width: Float
        val height: Float
        if (horizontal) {
            charSize = min(
                min(
                    0.86f * photoW / (H_ADV * maxChars),
                    0.5f * photoH / (LINE_H * lines.size),
                ),
                0.052f * photoH,
            )
            width = charSize * H_ADV * maxChars
            height = charSize * (LINE_H * (lines.size - 1) + 1.3f)
        } else {
            charSize = min(
                min(
                    0.58f * photoH / (ADVANCE * maxChars),
                    0.5f * photoW / (COL_W * lines.size),
                ),
                0.066f * photoH,
            )
            width = charSize * COL_W * lines.size
            height = charSize * ADVANCE * maxChars
        }

        val pad = charSize * 0.4f // 阴影与笔画出血
        return Layout(
            lines, horizontal, charSize, pad,
            ceil(width + 2 * pad).toInt(), ceil(height + 2 * pad).toInt(),
        )
    }

    /** 覆盖层左上角在照片里的坐标；fx/fy ∈ 0..1 是可用空间里的比例，scale 是用户缩放。 */
    fun overlayPos(
        photoW: Int, photoH: Int, layout: Layout,
        fx: Float, fy: Float, scale: Float,
    ): Pair<Float, Float> {
        val x = fx.coerceIn(0f, 1f) * max(1f, photoW - layout.width * scale)
        val y = fy.coerceIn(0f, 1f) * max(1f, photoH - layout.height * scale)
        return x to y
    }

    /** 落字区域是否偏亮 → 用墨色；偏暗 → 用纸白。 */
    fun regionIsBright(photo: Bitmap, region: Rect): Boolean {
        val r = Rect(
            region.left.coerceIn(0, photo.width - 1),
            region.top.coerceIn(0, photo.height - 1),
            region.right.coerceIn(1, photo.width),
            region.bottom.coerceIn(1, photo.height),
        )
        if (r.width() <= 0 || r.height() <= 0) return true
        val steps = 12
        var sum = 0L
        var n = 0
        for (i in 0 until steps) {
            for (j in 0 until steps) {
                val x = r.left + r.width() * i / steps
                val y = r.top + r.height() * j / steps
                val c = photo.getPixel(x.coerceIn(0, photo.width - 1), y.coerceIn(0, photo.height - 1))
                val lum = (299 * ((c shr 16) and 0xFF) +
                        587 * ((c shr 8) and 0xFF) +
                        114 * (c and 0xFF)) / 1000
                sum += lum
                n++
            }
        }
        return sum / n > 140
    }

    /** 渲染透明底的题字覆盖层（RES 倍超采样）。 */
    fun renderOverlay(layout: Layout, typeface: Typeface, darkInk: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(
            ceil(layout.width * RES).toInt(),
            ceil(layout.height * RES).toInt(),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bmp)
        canvas.scale(RES, RES)
        val charSize = layout.charSize

        val ink = if (darkInk) INK_DARK else INK_LIGHT
        val shadow = if (darkInk) 0x59FFF3DC else 0x66000000

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textAlign = Paint.Align.CENTER
            textSize = charSize
            color = ink
            setShadowLayer(charSize * 0.11f, 0f, charSize * 0.035f, shadow)
        }

        if (layout.horizontal) {
            var y = layout.pad + charSize * 0.92f
            for (line in layout.lines) {
                // 每行居中，逐字等距，方块感更接近课本排版
                var x = layout.width / 2f - (line.length - 1) * charSize * H_ADV / 2f
                for (ch in line) {
                    canvas.drawText(ch.toString(), x, y, paint)
                    x += charSize * H_ADV
                }
                y += charSize * LINE_H
            }
        } else {
            var x = layout.width - layout.pad - charSize * COL_W / 2f
            for (line in layout.lines) {
                var y = layout.pad + charSize * 0.85f
                for (ch in line) {
                    canvas.drawText(ch.toString(), x, y, paint)
                    y += charSize * ADVANCE
                }
                x -= charSize * COL_W
            }
        }
        return bmp
    }

    /** 按当前位置与缩放把覆盖层合成到照片上（导出用）；caption 为左下角日签题款。 */
    fun bake(
        photo: Bitmap, overlay: Bitmap, layout: Layout,
        fx: Float, fy: Float, scale: Float,
        caption: String? = null, captionTypeface: Typeface? = null,
    ): Bitmap {
        val out = photo.copy(Bitmap.Config.ARGB_8888, true)
        val (x, y) = overlayPos(photo.width, photo.height, layout, fx, fy, scale)
        val dst = RectF(x, y, x + layout.width * scale, y + layout.height * scale)
        val canvas = Canvas(out)
        canvas.drawBitmap(overlay, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))

        if (!caption.isNullOrBlank()) {
            val size = photo.height * 0.021f
            val pad = size * 1.2f
            val bright = regionIsBright(
                photo,
                Rect(
                    pad.toInt(), (photo.height - pad - size * 1.4f).toInt(),
                    (pad + size * caption.length).toInt(), photo.height - (pad * 0.4f).toInt(),
                ),
            )
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = captionTypeface
                textSize = size
                color = if (bright) INK_DARK else INK_LIGHT
                alpha = 225
                setShadowLayer(size * 0.12f, 0f, size * 0.04f,
                    if (bright) 0x40FFF3DC else 0x59000000)
            }
            canvas.drawText(caption, pad, photo.height - pad, paint)
        }
        return out
    }
}
