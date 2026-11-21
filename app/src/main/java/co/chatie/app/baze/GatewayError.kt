package co.chatie.app.baze

/**
 * A gateway failure translated into something worth showing a person, with the
 * fix where the docs give one. [retryAfterSeconds] is set only for 429.
 */
class GatewayError(
    val status: Int,
    val code: Int?,
    val rawMessage: String,
    val userMessage: String,
    val retryAfterSeconds: Int? = null,
    val reason: String? = null,
) : Exception(userMessage) {

    val isRetryable: Boolean get() = status == 429 || status in 500..599

    /**
     * What the gateway actually said, for showing under [userMessage].
     * A friendly message that hides the server's own wording makes a wrong
     * diagnosis easy and a right one impossible.
     */
    val diagnostic: String
        get() = buildString {
            append(if (status == 0) "요청 실패" else "HTTP $status")
            code?.let { if (it != status) append(" · code $it") }
            reason?.let { append(" · reason=$it") }
            if (rawMessage.isNotBlank() && rawMessage != userMessage) {
                append(" · ")
                append(rawMessage)
            }
        }

    companion object {
        fun of(status: Int, body: String, retryAfter: Int?, json: kotlinx.serialization.json.Json): GatewayError {
            // Cloudflare blocks before the gateway is reached and answers in plain text.
            if (body.contains("error code: 1010")) {
                return GatewayError(
                    status = status,
                    code = 1010,
                    rawMessage = body.take(200),
                    userMessage = "요청이 게이트웨이에 닿기 전에 차단되었습니다 (User-Agent 누락). 앱 버그이니 제보해 주세요.",
                )
            }

            val detail = runCatching {
                json.decodeFromString<ErrorEnvelope>(body).detail
            }.getOrNull()
            val code = detail?.code
            val raw = detail?.message ?: body.take(300).ifBlank { "HTTP $status" }
            val reason = Regex("\"reason\"\\s*:\\s*\"([a-z_]+)\"").find(body)?.groupValues?.getOrNull(1)

            val message = when {
                status == 401 -> when (reason) {
                    "revoked" -> "API 키가 해지되었습니다. 설정에서 새 키를 넣어주세요."
                    "disabled" -> "API 키가 꺼져 있습니다. BAZE의 API Gateway 화면에서 다시 켜주세요."
                    "admin_disabled" -> "조직 관리자가 이 키를 껐습니다. 관리자에게 문의하세요."
                    "held" -> "보안 검토로 키가 보류되었습니다. BAZE 지원에 문의하세요."
                    else -> "API 키가 유효하지 않습니다. 설정에서 다시 확인해 주세요."
                }
                status == 402 && code == 6010 ->
                    "남은 크레딧이 이번 요청의 예상 차감액보다 적습니다. 최대 출력 토큰을 낮추거나 크레딧을 충전하세요."
                status == 402 ->
                    "크레딧이 부족합니다. BAZE에서 충전한 뒤 다시 시도해 주세요."
                status == 403 ->
                    "이 모델이 조직에 활성화되어 있지 않거나 게이트웨이로 제공되지 않습니다. 다른 모델을 선택해 보세요."
                status == 404 ->
                    "모델을 찾을 수 없습니다. 모델 목록을 새로 받아 다시 선택해 주세요."
                status == 413 ->
                    "요청이 너무 큽니다 (25MB 초과). 대화 기록 예산을 줄여보세요."
                status == 429 ->
                    "호출이 너무 잦습니다 (60초에 120건 제한)." +
                        (retryAfter?.let { " ${it}초 뒤에 다시 시도합니다." } ?: "")
                status == 503 ->
                    "BAZE가 점검 중입니다. 잠시 뒤 다시 시도해 주세요."
                status >= 500 ->
                    "BAZE 또는 모델 제공업체 쪽 오류입니다 (HTTP $status). 잠시 뒤 다시 시도해 주세요."
                else -> raw
            }

            return GatewayError(
                status = status,
                code = code,
                rawMessage = raw,
                userMessage = message,
                retryAfterSeconds = retryAfter,
                reason = reason,
            )
        }
    }
}
