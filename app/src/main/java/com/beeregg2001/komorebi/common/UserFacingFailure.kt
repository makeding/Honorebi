package com.beeregg2001.komorebi.common

import kotlinx.coroutines.TimeoutCancellationException
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Safe to display: never includes request URLs, credentials or raw response bodies. */
data class UserFacingFailure(val message: String, val code: String)

fun userFacingFailure(error: Throwable?, fallbackCode: String): UserFacingFailure {
    val causes = generateSequence(error) { it.cause }.take(8).toList()
    val http = causes.filterIsInstance<HttpException>().firstOrNull()
    return when {
        causes.any { it.message == "NHK_EXCLUDED" } ->
            UserFacingFailure("除外設定により、この番組は利用できません。", "NHK_EXCLUDED")
        http != null -> UserFacingFailure(
            when (http.code()) {
                401, 403 -> "サーバーへのアクセスが許可されませんでした。接続設定を確認してください。"
                404 -> "対象の番組がサーバーに見つかりません。"
                409 -> "番組の状態が変わりました。最新の状態を確認して再試行してください。"
                in 500..599 -> "サーバーでエラーが発生しました。再試行してください。"
                else -> "サーバーが操作を受け付けませんでした。再試行してください。"
            }, "HTTP_${http.code()}",
        )
        causes.any { it is SocketTimeoutException || it is TimeoutCancellationException } ->
            UserFacingFailure("サーバーからの応答がありませんでした。接続を確認して再試行してください。", "${fallbackCode}_TIMEOUT")
        causes.any { it is UnknownHostException } ->
            UserFacingFailure("サーバーが見つかりません。接続設定とネットワークを確認してください。", "${fallbackCode}_HOST")
        causes.any { it is IOException } ->
            UserFacingFailure("サーバーと通信できませんでした。接続を確認して再試行してください。", "${fallbackCode}_NETWORK")
        else -> UserFacingFailure("サーバーから利用可能な結果を取得できませんでした。再試行してください。", fallbackCode)
    }
}
