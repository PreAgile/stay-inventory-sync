# stay-inventory-sync 리뷰 가이드

이 저장소는 **다채널 숙박 재고의 정합성**을 증명하는 것이 목적이다.
기능의 폭이나 코드 스타일보다 **정확성 위반 가능성**을 우선 검토해 주기 바란다.

스택: Kotlin + Spring Boot 3.x + JDK 21 + PostgreSQL + Kotest + Testcontainers

## 최우선 검토 항목

### 1. 동시성 정확성

- 다중 행 락 획득 시 순서가 고정되어 있는가 (`stayDate` 오름차순).
  순서가 고정되지 않으면 다일 예약 간 데드락이 발생한다. **Critical**
- 락 보유 구간 안에서 외부 HTTP 호출이 일어나는가. **Critical**
- 재고 카운터 변경이 트랜잭션 밖에서 일어날 수 있는가. **Critical**

### 2. 트랜잭션 경계

- `OutboxEvent` 저장이 도메인 변경과 동일 트랜잭션인가.
  분리되면 dual-write 문제가 그대로 재발한다. **Critical**
- `@Transactional`이 self-invocation으로 무효화되는 경로가 있는가. **High**
- Kotlin에서 `@Transactional` 대상 메서드가 `open`인가
  (`kotlin-spring` 플러그인 적용 여부 확인). **High**

### 3. 멱등성

- 중복 웹훅 수신 시 4xx를 반환하는 경로가 있는가.
  채널이 이를 실패로 간주해 최대 24시간 재시도한다. **High**
- 어댑터 호출에 멱등키가 누락되었는가. **High**

### 4. 도메인 규칙

- 숙박 기간은 `[checkIn, checkOut)` 반개구간이다.
  체크아웃 당일 재고를 차감하면 안 된다. **High**
- 재고 복원은 `CONFIRMED -> CANCELED`에서만 일어난다.
  `PENDING -> CANCELED`는 차감된 적이 없다. **High**
- 상태 전이는 명시적으로 선언되어야 하며 불법 전이는 예외여야 한다. **Medium**

## Kotlin 관련 검토 항목

### 반드시 지적할 것

- **JPA 엔티티가 `data class`로 선언된 경우. Critical**
  `equals`/`hashCode`가 전체 프로퍼티 기반이라 지연 로딩 프록시 및
  영속성 컨텍스트의 동일성 판단과 충돌한다.
  엔티티는 일반 `class`로 두고 `id` 기반 `equals`/`hashCode`를 직접 정의해야 한다.
- 엔티티 컬렉션 프로퍼티가 `var`로 재할당 가능한 경우. **High**
- 플랫폼 타입(`String!`)이 non-null로 취급되어 NPE 가능성이 있는 경로. **High**
- `!!` 사용. 도메인 불변식이라면 `requireNotNull`에 메시지를 붙여야 한다. **Medium**
- `when`이 sealed 타입에 대해 `else`로 뭉뚱그린 경우. **Medium**
  망라성 검사를 무력화해 새 케이스 추가 시 누락을 놓친다.

### 사용 금지 기능 (등장하면 지적할 것)

ADR-0005에서 의도적으로 제외한 기능들이다.

- 코루틴 (JDK 21 가상 스레드를 사용한다)
- DSL 빌더, `inline` + `reified`, context receivers
- 위임(`by`) 남용, 연산자 오버로딩

## 지적하지 말 것

- **Kafka / 메시지 브로커 도입 권유** — ADR-0004에서 도입 조건과 경로를 명시했다
- **`@TransactionalEventListener` 대체 제안** — ADR-0003에서 기각한 대안이다
- **코루틴 도입 권유** — ADR-0005에서 명시적으로 제외했다
- **Lincheck 등 모델 체킹 도구 권유** — ADR-0005에 적용 불가 사유를 기술했다
- **인증/권한 추가 제안** — 범위에서 명시적으로 제외했다 (`docs/02-scope.md`)
- **프론트엔드 관련 일체** — 범위 밖이다
- 단순 포맷팅, import 순서, 주석 부재

`docs/` 하위 파일에 대해서는 문서의 주장과 구현의 불일치만 지적해 달라.

## 코드 컨벤션

- `val`이 기본. `var`는 정당화가 필요하다
- 테스트는 Kotest `FunSpec`, Given-When-Then 주석 구조
- 테스트 이름은 증명 대상을 서술한다.
  예: `"동시 요청 하에서 오버부킹은 발생하지 않는다"`
- 예외는 도메인 예외로 감싸고 HTTP 상태 매핑은 `@RestControllerAdvice`에서 처리한다
