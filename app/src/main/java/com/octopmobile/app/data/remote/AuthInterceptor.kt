package com.octopmobile.app.data.remote

import com.octopmobile.app.data.prefs.SecurePrefs
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 注入 `Authorization: Bearer`，并处理服务端的滑动续期响应头（docs/protocol.md §2、§8）。
 *
 * [baseUrl] 是本客户端的实例地址，用于校验令牌归属 —— 用户改了实例地址后，
 * [SecurePrefs] 里可能还留着旧实例的令牌，那个令牌发给新实例只会换来一个 401。
 *
 * 登录请求要跳过：此时手里可能还留着上一轮的旧令牌，带上只会让服务端多一次无意义的解析。
 */
class AuthInterceptor(
    private val securePrefs: SecurePrefs,
    private val baseUrl: String,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val isLogin = original.url.encodedPath.endsWith(LOGIN_PATH)

        val request = when {
            isLogin -> original
            original.header(HEADER_AUTH) != null -> original
            else -> {
                val token = securePrefs.tokenFor(baseUrl)
                if (token.isNullOrBlank()) {
                    original
                } else {
                    original.newBuilder().header(HEADER_AUTH, "Bearer $token").build()
                }
            }
        }

        val response = chain.proceed(request)

        // 续期令牌必须回写，否则令牌一到期就要用户重新登录。
        response.header(HEADER_RENEWED_TOKEN)?.let { securePrefs.updateToken(it) }

        return response
    }

    private companion object {
        const val HEADER_AUTH = "Authorization"
        const val HEADER_RENEWED_TOKEN = "X-Octop-Access-Token"
        const val LOGIN_PATH = "/api/auth/login"
    }
}
