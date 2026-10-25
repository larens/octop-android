package com.octopmobile.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 凭据存储（docs/spec.md §15）：登录令牌只在加密容器里，不落明文 DataStore。
 *
 * 只保存「当前实例」的会话 —— 本版不做多实例并行管理（spec §20），换地址即视为换实例，
 * 旧令牌立刻失效（[tokenFor] 会拒绝不匹配的 host）。
 *
 * 读取都是内存态（`SharedPreferences` 首次加载后即有进程内缓存），因此
 * [com.octopmobile.app.data.remote.AuthInterceptor] 这类同步调用点可以直接读。
 */
class SecurePrefs(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy { open() }

    /**
     * Keystore 里的密钥可能被系统回收（换机恢复、清数据不全），文件也可能损坏。
     * 那种情况下 `create` 会抛异常，如果不在这里重建，用户会陷入「应用再也起不来」的死局。
     */
    private fun open(): SharedPreferences =
        runCatching { create() }.getOrElse {
            appContext.deleteSharedPreferences(FILE_NAME)
            create()
        }

    private fun create(): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** 令牌所属实例。 */
    val host: String? get() = prefs.getString(KEY_HOST, null)?.takeIf { it.isNotBlank() }

    val token: String? get() = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    val username: String? get() = prefs.getString(KEY_USERNAME, null)

    val displayName: String? get() = prefs.getString(KEY_DISPLAY_NAME, null)

    /** 只有 host 对得上才算登录态：换实例后旧令牌不能拿来用。 */
    fun tokenFor(baseUrl: String): String? = token.takeIf { host == baseUrl }

    fun saveSession(baseUrl: String, token: String, username: String?, displayName: String?) {
        prefs.edit()
            .putString(KEY_HOST, baseUrl)
            .putString(KEY_TOKEN, token)
            .putString(KEY_USERNAME, username)
            .putString(KEY_DISPLAY_NAME, displayName)
            .apply()
    }

    /**
     * 滑动续期：服务端在令牌剩余寿命不足 1/3 时会通过响应头 `X-Octop-Access-Token`
     * 回传新令牌（docs/protocol.md §2）。不回写就等于每次过期都得重新登录。
     */
    fun updateToken(newToken: String) {
        if (newToken.isBlank()) return
        prefs.edit().putString(KEY_TOKEN, newToken).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val FILE_NAME = "octop_secure_prefs"
        const val KEY_HOST = "session_host"
        const val KEY_TOKEN = "access_token"
        const val KEY_USERNAME = "username"
        const val KEY_DISPLAY_NAME = "display_name"
    }
}
