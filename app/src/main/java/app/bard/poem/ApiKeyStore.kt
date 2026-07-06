package app.bard.poem

import android.content.Context

/**
 * 默认走自家后端代理（key 在服务端）。
 * 用户若在设置里填了自己的 OpenRouter key，则直连 OpenRouter。
 */
object ApiKeyStore {
    private const val PREFS = "bard"
    private const val KEY = "api_key"

    fun overrideValue(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    fun setOverride(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, value.trim())
            .apply()
    }
}
