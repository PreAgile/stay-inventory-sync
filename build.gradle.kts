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
    // 툴체인으로 고정한다. 로컬 JAVA_HOME 이 무엇이든 21 로 컴파일된다 —
    // "내 로컬에서는 됐다" 를 없애는 쪽이 목적이다.
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

    // Testcontainers — H2 를 쓰지 않는 이유가 FOR UPDATE 의 의미 차이다.
    // 락 검증이 성립해야 하므로 실제 PostgreSQL 을 띄운다.
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")

    testImplementation("org.awaitility:awaitility")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

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
