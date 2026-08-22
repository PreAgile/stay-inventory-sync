package dev.preagile.stayinventory.domain

/**
 * DB `CHECK` 제약이 있는 값 집합만 enum 으로 둔다.
 *
 * `channel` 은 여기 없다. `V1__init.sql` 이 그 컬럼에 `CHECK` 를 걸지 않았고,
 * 코드가 DB 보다 좁으면 채널이 하나 늘 때마다 배포가 필요해진다.
 * **제약의 위치를 코드와 DB 에서 같게 유지한다.**
 */

/** 룸타입이 즉시확정인가 승인형인가. `HELD` 다음 상태를 가른다 (ADR-0010 결정 7). */
enum class BookingMode { INSTANT, ON_REQUEST }

enum class OutboxStatus { PENDING, PUBLISHED, DEAD }

/** 인바운드 알림이 무엇에 관한 것인가. 재고 축과 정책 축을 가른다 (ADR-0009). */
enum class InboundKind { BOOKING, POLICY }

/** `IGNORED` 는 실패가 아니다 -- 우리가 보낸 것이 그대로 돌아온 에코 등. */
enum class InboundStatus { PENDING, PROCESSED, IGNORED, DEAD }

enum class ChannelPolicyKind { CLOSED, CAP, OFFSET }

/** 정책을 누가 정했는가. "이 값은 우리가 정했나 현장이 정했나" 를 나중에 물을 수 있어야 한다. */
enum class ChannelPolicySource { OURS, CHANNEL }
