# ADR-0005. Kotlin을 쓰되, 의도적으로 좁은 부분집합만 사용한다

## 상태
확정

## 맥락

JVM 백엔드에서 Kotlin은 Java 대비 표현력이 높다.
그러나 표현력은 양날이다. 언어 기능을 넓게 쓸수록
**코드를 읽는 사람이 알아야 할 문법의 양이 늘어난다.**

3일짜리 실험 저장소의 독자는 이 코드베이스에 익숙하지 않은 사람이다.
읽는 비용이 곧 이 프로젝트의 전달력이다.

## 결정

**Kotlin + Spring Boot 3.x + JDK 21**을 사용한다.
단, 사용할 언어 기능을 명시적으로 제한한다.

### 사용하는 것

| 기능 | 용도 |
|---|---|
| `data class` | 이벤트 페이로드, DTO, 값 객체 |
| `sealed interface` + `when` | 어댑터 결과 타입의 망라적 처리 |
| null 안전성 (`?`, `?:`, `requireNotNull`) | 도메인 불변식의 타입 수준 표현 |
| `val` 기본 | 불변을 기본값으로 |
| 확장 함수 | 3개 이내. 날짜 범위 생성 등 |
| Kotest | 테스트 |

### 사용하지 않는 것

| 기능 | 이유 |
|---|---|
| 코루틴 | 데이터 접근이 블로킹 JDBC다. 논블로킹 드라이버 없이는 이득이 0이고, R2DBC로 가면 JPA가 사라져 이 ADR의 나머지가 무너진다. 아래 "코루틴을 쓰지 않는 진짜 이유" 참고 |
| DSL 빌더 | 읽는 비용 대비 이득이 없다 |
| `inline` + `reified` | 이 규모에서 정당화되지 않는다 |
| context receivers | 실험적이며 독자에게 낯설다 |
| 위임 (`by`) | 위임 대상이 명시적이지 않아 추적 비용이 든다 |
| 연산자 오버로딩 | 도메인 개념을 기호로 숨긴다 |

**"쓸 수 있다"와 "써야 한다"는 다르다.**
이 목록은 능력의 한계가 아니라 선택의 기록이다.

## sealed interface를 쓰는 유일한 지점

채널 어댑터의 호출 결과다.

```kotlin
sealed interface ChannelSyncResult {
    data class Success(val channelRef: String) : ChannelSyncResult
    data class RateLimited(val retryAfterSeconds: Long?) : ChannelSyncResult
    data class Retryable(val cause: String) : ChannelSyncResult
    data class Permanent(val cause: String) : ChannelSyncResult
}
```

릴레이는 `when`으로 네 케이스를 망라 처리한다.
새 결과 유형이 추가되면 **컴파일 에러로 누락을 잡는다.**

`retryAfterSeconds` 가 널 허용인 것 자체가 도메인 사실이다 —
**`Retry-After` 를 주지 않는 채널이 있다.** 그때는 우리 백오프를 쓴다.

이 구분이 중요한 이유는 `RateLimited`를 `Permanent`로 오분류하면
정상 동작 중인 채널에 대한 이벤트가 DLQ로 떨어지기 때문이다.
레이트 리밋은 실패가 아니라 **나중에 다시 하라는 신호**다.

## JPA 엔티티에는 data class를 쓰지 않는다

Kotlin을 쓸 때 가장 흔한 실수이므로 명시한다.

`data class`의 `equals`/`hashCode`는 모든 프로퍼티를 기반으로 생성된다.
JPA 엔티티에서는 다음 문제가 생긴다.

- 지연 로딩 프록시에서 `hashCode` 호출 시 의도치 않은 초기화가 발생
- 영속성 컨텍스트의 동일성 판단과 `equals`의 동등성 판단이 어긋남
- `id`가 아직 null인 비영속 상태와 영속 상태의 해시가 달라져
  컬렉션에 넣은 뒤 저장하면 찾지 못함

**엔티티는 일반 `class`로 선언하고, `equals`/`hashCode`는 `id` 기반으로 직접 정의한다.**
`data class`는 엔티티 경계 바깥(DTO, 이벤트 페이로드)에서만 쓴다.

## 컴파일러 플러그인

```kotlin
plugins {
    kotlin("plugin.spring")  // allOpen: @Component 등을 open으로
    kotlin("plugin.jpa")     // noArg: @Entity에 기본 생성자 생성
}
```

Kotlin 클래스는 기본이 `final`이다.
Spring의 CGLIB 프록시와 Hibernate의 지연 로딩 프록시는 모두 상속을 요구하므로
이 플러그인 없이는 런타임에 실패한다.

`noArg`는 바이트코드 수준에서만 기본 생성자를 만들며,
Kotlin 코드에서는 여전히 호출할 수 없다. 안전성이 유지된다.

## Lincheck을 쓰지 않는다

Lincheck은 JVM 메모리 모델 상의 **인메모리 동시 자료구조**에 대해
선형화 가능성(linearizability)을 모델 체킹으로 검증하는 도구다.

이 프로젝트의 동시성은 인메모리가 아니라
**DB 트랜잭션과 행 수준 락**에 있다. 검증 대상이 다르다.

Lincheck이 탐색하는 인터리빙 공간은 JVM 스레드 스케줄링이지,
PostgreSQL의 락 대기 그래프나 MVCC 스냅숏이 아니다.
따라서 적용해도 이 시스템이 주장하는 정합성을 증명하지 못한다.

**대신 실제 PostgreSQL 위에서 동시 요청을 발생시키는 통합 테스트로 검증한다.**
`docs/03-testing-strategy.md` 참고.

도구의 적용 범위를 아는 것이 도구를 쓰는 것보다 중요하다고 판단했다.

## 기각한 대안: Java 21

`record`, `sealed interface`, pattern matching으로
이 프로젝트에 필요한 표현은 대부분 가능하다.

기각 이유는 두 가지다.

1. **엔티티/DTO 경계에서의 보일러플레이트.** Java에서는 엔티티에 여전히
   getter/setter 또는 Lombok이 필요하다. 의존성 하나를 아끼는 편이 낫다.
2. **null 안전성.** 이 도메인은 `channel_reservation_id`처럼
   채널에 따라 존재 여부가 갈리는 필드가 있다.
   타입 수준에서 강제되는 편이 실수를 줄인다.

## 코루틴을 쓰지 않는 진짜 이유

이 절은 최초 작성 시의 근거를 대체한다. 정정 이력은 문서 끝에 남긴다.

### 1. 블로킹 JDBC 위에서 코루틴은 이득이 0이다

이 설계의 심장은 `SELECT ... FOR UPDATE` + JPA + `@Transactional`이다.
JDBC는 블로킹 API이므로, `suspend`를 붙여도 호출 지점에서 캐리어 스레드가 그대로 묶인다.

```kotlin
suspend fun deduct(...) {          // suspend를 붙여도
    repository.lockForUpdate(...)  // JDBC 호출. 캐리어 스레드가 블로킹된다
}
```

얻는 것은 상태 기계 변환 비용과 `suspend` 전염뿐이다.

이득을 보려면 논블로킹 드라이버(R2DBC)로 가야 하는데,
**R2DBC는 JPA/Hibernate를 쓸 수 없다.**
그러면 이 ADR이 공들여 정리한 엔티티 `data class` 금지, 영속성 컨텍스트 동일성,
`allOpen`/`noArg` 플러그인 논의가 통째로 무의미해진다.

즉 코루틴 도입은 언어 기능 하나를 고르는 결정이 아니라
**데이터 접근 스택 전체를 교체하는 결정**이다.

### 2. 트랜잭션 컨텍스트가 ThreadLocal 기반이다

Spring의 `TransactionSynchronizationManager`는 `ThreadLocal`에 트랜잭션 상태를 둔다.
플랫폼 스레드에서는 그대로 동작하지만,
코루틴은 디스패처를 넘나들며 suspend할 때 `ThreadLocal`이 따라가지 않아
별도의 컨텍스트 전파 장치가 필요하다.

그런데 이 프로젝트의 **절대 규칙 3**은
`OutboxEvent` 저장이 도메인 변경과 같은 트랜잭션에 있어야 한다는 것이다(ADR-0003).
코루틴은 하필 그 트랜잭션 경계를 취약하게 만드는 도구다.

**코루틴을 쓰지 않는 이유는 필요가 없어서가 아니라, 이 프로젝트의 핵심 주장을 위협하기 때문이다.**

---

## 정정 이력

**최초 작성 시의 근거** — "I/O 바운드 워크로드에 JDK 21 가상 스레드로 충분하다."

이 문장은 두 가지 문제가 있었다.

1. **사실관계**: 가상 스레드는 Java 언어 기능이 아니라 JVM 런타임 기능이므로
   "Kotlin인데 가상 스레드"라는 구도 자체가 성립하지 않는다.
   Kotlin에서도 동일한 JDK API이며, 실제로는 `spring.threads.virtual.enabled=true`
   설정 한 줄이다. 이 부분은 틀리지 않았으나 오해를 부르는 서술이었다.
2. **논리 구조**: 코루틴 기각이 가상 스레드 사용에 의존하고 있었다.
   가상 스레드를 쓰지 않기로 하면(ADR-0006) 기각 근거가 함께 무너지는 구조였다.

위 "진짜 이유" 두 항목은 가상 스레드의 사용 여부와 무관하게 성립한다.
가상 스레드 자체에 대한 판단은 **ADR-0006**으로 분리했다.

## 결과

- 코드 리뷰 도구 설정에 사용 금지 기능 목록을 전달한다 (`.coderabbit.yaml`)
- 위 목록 밖의 Kotlin 기능이 코드에 등장하면 그것은 리뷰 대상이다
- 전환 비용: 금요일 시점에 진행이 막히면 Java로 전환한다.
  이 시점의 코드량에서 전환 비용은 1시간 이내로 추정된다
