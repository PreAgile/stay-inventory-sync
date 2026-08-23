package dev.preagile.stayinventory.api

import org.slf4j.LoggerFactory
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.QueryTimeoutException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.CannotGetJdbcConnectionException
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * 예외를 HTTP 로 옮기는 한 곳 (`#65`).
 *
 * ## 왜 필요한가
 *
 * 커넥션 풀 고갈 · 커넥션 실패 · 락 타임아웃이 **전부 `500`** 으로 나갔다.
 * 그러면 호출부가 **"우리 잘못" 과 "잠시 후 다시" 를 구분할 수 없다.**
 *
 * | | 호출부에게 주는 지시 |
 * |---|---|
 * | `500` | 우리 버그다. 재시도해도 같다 |
 * | `503` + `Retry-After` | 일시적이다. 지시한 시간 뒤에 다시 오라 |
 *
 * 뭉개면 **채널이 최대 24시간 재시도하거나 반대로 포기한다.** 둘 다 우리가
 * 의도한 것이 아니다 (절대 규칙 5).
 *
 * ## 컨트롤러마다 붙이지 않는다
 *
 * `IllegalArgumentException` 핸들러가 `ReservationController` 에만 있었다.
 * 그래서 `InventoryDiffController` 의 `require(from < to)` 는 **`400` 이 아니라
 * `500`** 이 됐다 — 운영자가 날짜를 뒤집으면 **잘못된 입력이 장애로 보고된다.**
 *
 * 전역으로 올리면 **엔드포인트가 늘 때 자동으로 적용된다.** 컨트롤러마다 붙이는
 * 구조는 붙이는 것을 잊을 자리를 만든다.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 잘못된 입력. 호출부가 **고칠 수 있는** 요청이다.
     *
     * `409` 와 가르는 기준은 "고칠 것이 있는가" 다. 재고가 없어서 못 받는 것은
     * 요청이 올바르므로 `409` 이고, 날짜가 뒤집힌 것은 `400` 이다.
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun onBadRequest(e: IllegalArgumentException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(
            ErrorResponse(status = "BAD_REQUEST", reason = e.message ?: "요청이 올바르지 않다"),
        )

    /**
     * DB 에 닿지 못했다. **일시적이다.**
     *
     * `CannotGetJdbcConnectionException` 은 풀 고갈과 DB 다운을 함께 덮는다.
     * 둘을 구분하지 않는 이유는 **호출부가 할 일이 같기 때문**이다 — 잠시 후 다시.
     */
    @ExceptionHandler(
        CannotGetJdbcConnectionException::class,
        CannotCreateTransactionException::class,
    )
    fun onDatabaseUnavailable(e: Exception): ResponseEntity<ErrorResponse> {
        // warn 이다. error 로 찍으면 DB 가 흔들릴 때 로그가 error 로 도배되고
        // 진짜 버그가 그 안에 묻힌다.
        log.warn("DB 에 닿지 못했다: {}", e.message)
        return retryLater("DATABASE_UNAVAILABLE", "일시적으로 처리할 수 없다", DB_RETRY_SECONDS)
    }

    /**
     * 락을 얻지 못했다. **경합이지 버그가 아니다.**
     *
     * `lock_timeout = 5s` 가 세션에 걸려 있으므로 무한 대기는 없다. 그 타임아웃이
     * 여기 온다 — 재시도하면 대개 성공한다.
     *
     * **데드락은 여기 오지 않는다.** 락 순서를 고정해 구조적으로 불가능하게
     * 만들었고(ADR-0011), 만약 온다면 그것은 **락 순서가 틀렸다는 신호**이므로
     * 흡수하지 않고 `500` 으로 남긴다.
     */
    @ExceptionHandler(CannotAcquireLockException::class, QueryTimeoutException::class)
    fun onLockTimeout(e: Exception): ResponseEntity<ErrorResponse> {
        log.warn("락 획득 실패: {}", e.message)
        return retryLater("LOCK_TIMEOUT", "지금 경합이 심하다", LOCK_RETRY_SECONDS)
    }

    private fun retryLater(
        status: String,
        reason: String,
        seconds: Long,
    ): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            // Retry-After 를 함께 준다. 503 만 주면 호출부가 자기 백오프를 쓰고,
            // 그 값이 우리 회복 시간과 무관하다.
            .header(HttpHeaders.RETRY_AFTER, seconds.toString())
            .body(ErrorResponse(status = status, reason = reason, retryAfterSeconds = seconds))

    companion object {
        /**
         * DB 회복을 기다리는 시간.
         *
         * 풀 고갈은 진행 중인 요청이 끝나면 풀리므로 짧게 잡는다.
         * 길게 잡으면 이미 회복된 뒤에도 호출부가 기다린다.
         */
        const val DB_RETRY_SECONDS = 5L

        /** 락 경합은 `lock_timeout`(5s) 안에 판정되므로 그보다 짧게 잡는다. */
        const val LOCK_RETRY_SECONDS = 2L
    }
}

/**
 * 오류 응답. **무엇을 해야 하는지까지 적는다** — 상태코드만으로는 알 수 없다.
 */
data class ErrorResponse(
    val status: String,
    val reason: String,
    val retryAfterSeconds: Long? = null,
)
