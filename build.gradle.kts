plugins {
    // plugin.spring 은 @Component 계열 클래스와 메서드를 자동으로 open 으로 만든다.
    // Kotlin 의 클래스는 기본이 final 이고 Spring 의 CGLIB 프록시는 상속을 요구한다.
    kotlin("plugin.spring") version "2.1.20"
    // plugin.jpa 는 엔티티에 no-arg 생성자를 합성한다. JPA 스펙이 요구하는데
    // Kotlin 의 주 생성자만으로는 만족되지 않는다. 엔티티마다 손으로 쓰지 않기 위한 것이다.
    kotlin("plugin.jpa") version "2.1.20"
    kotlin("jvm") version "2.1.20"

    id("org.springframework.boot") version "3.4.4"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.preagile"
version = "0.1.0-SNAPSHOT"

kotlin {
    // 컴파일에 쓰는 JDK 를 21 로 고정한다. 로컬에 상위 JDK 가 있어도 산출물은 21 이다.
    //
    // 이것이 보장하지 않는 것: **Gradle 자신이 도는 JVM.**
    // 툴체인은 컴파일러만 고르고, Gradle 과 Kotlin 플러그인은 JAVA_HOME 에서 돈다.
    // 그래서 실행 JVM 은 settings.gradle.kts 가 따로 검사한다.
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    implementation("org.flywaydb:flyway-core")
    // Flyway 10 부터 DB 별 지원이 별도 모듈이다. 이것이 없으면
    // "Unsupported Database: PostgreSQL" 로 마이그레이션이 죽는다.
    implementation("org.flywaydb:flyway-database-postgresql")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")

    // Kotest — JUnit5 플랫폼 위에서 돈다. runner 가 없으면 스펙이 발견되지 않는다.
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    // Kotest 로 Spring 컨텍스트 테스트를 쓰려면 SpringExtension 이 필요하다.
    // 이것이 없으면 @SpringBootTest 가 붙어도 컨텍스트가 뜨지 않고 생성자 주입이 안 된다 —
    // JUnit 으로 도망가지 않기 위한 의존이다 (AGENTS.md 테스트 규약).
    testImplementation("io.kotest.extensions:kotest-extensions-spring:1.3.0")

    // Testcontainers — H2 를 쓰지 않는 이유가 FOR UPDATE 의 의미 차이다.
    // 락 검증이 성립해야 하므로 실제 PostgreSQL 을 띄운다.
    //
    // org.testcontainers:junit-jupiter 는 넣지 않는다. 그 모듈이 주는 것은
    // @Testcontainers / @Container 라는 **JUnit 확장**뿐이고, 테스트 규약이
    // FunSpec 통일을 요구하므로 쓸 수 없다(AGENTS.md). 컨테이너는 평문 API 로
    // 직접 띄우거나(PostgresCapabilityTest) Spring 빈으로 선언한다(PostgresTestContainer).
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")

    testImplementation("org.awaitility:awaitility")
    // archunit-junit5 가 아니라 평문 archunit 이다. -junit5 변형이 주는 것은
    // @AnalyzeClasses / @ArchTest 라는 JUnit 확장이고, 규칙은 FunSpec 안에서
    // ArchUnit API 를 직접 호출해 검사한다(PR ③).
    testImplementation("com.tngtech.archunit:archunit:1.3.0")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    // SchemaMatchesErdTest 는 이 두 파일을 읽어 대조한다. 태스크 입력으로
    // 선언하지 않으면 문서만 고친 경우 Gradle 이 UP-TO-DATE 로 건너뛰고,
    // 드리프트를 만들어도 초록불이 나온다 — 가드가 있으나 마나가 된다.
    inputs.file("docs/01-domain-model.md").withPathSensitivity(PathSensitivity.RELATIVE)
    // 파일이 아니라 디렉터리로 등록한다. V1 만 지정하면 V2 를 추가했을 때
    // 그 파일이 입력이 아니어서, 새 마이그레이션만 고친 경우 UP-TO-DATE 로 건너뛴다.
    inputs.dir("src/main/resources/db/migration").withPathSensitivity(PathSensitivity.RELATIVE)

    // 컨테이너를 띄우는 테스트가 섞이므로 포크를 늘리지 않는다.
    // 병렬로 여러 PostgreSQL 을 띄우면 CI 러너에서 메모리로 죽는다.
    maxParallelForks = 1

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        // 경고를 남겨 두면 쌓이고, 쌓이면 아무도 안 본다.
        allWarningsAsErrors.set(true)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}
