package com.octopmobile.app.net

import com.octopmobile.app.Const
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 校验用户填的地址是不是一个可用的 Octop 实例。
 *
 * 判据是 `GET /api/health` 返回 `{"ok":true,"db":true}` —— 这比「能打开首页」可靠得多：
 * 反代配错、只代理了静态资源、或者根本不是 Octop 都会在这里被拦住。
 */
object InstanceProbe {

    sealed interface Result {
        data object Ok : Result
        data class Fail(val reason: Reason, val detail: String? = null) : Result
    }

    enum class Reason {
        DNS,
        TIMEOUT,
        TLS,
        HTTP_STATUS,
        NOT_OCTOP,
        NETWORK,
        UNKNOWN,
    }

    private const val TIMEOUT_MS = 10_000

    suspend fun probe(baseUrl: String): Result = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(baseUrl + Const.HEALTH_PATH).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                return@withContext Result.Fail(Reason.HTTP_STATUS, code.toString())
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            if (json.optBoolean("ok") && json.optBoolean("db")) {
                Result.Ok
            } else {
                Result.Fail(Reason.NOT_OCTOP)
            }
        } catch (e: UnknownHostException) {
            Result.Fail(Reason.DNS)
        } catch (e: SocketTimeoutException) {
            Result.Fail(Reason.TIMEOUT)
        } catch (e: SSLException) {
            Result.Fail(Reason.TLS, e.message)
        } catch (e: JSONException) {
            Result.Fail(Reason.NOT_OCTOP)
        } catch (e: IOException) {
            Result.Fail(Reason.NETWORK, e.message)
        } catch (e: Exception) {
            Result.Fail(Reason.UNKNOWN, e.message)
        } finally {
            connection?.disconnect()
        }
    }
}
