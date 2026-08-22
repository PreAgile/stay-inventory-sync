// 단일 모듈이다. 패키지 경계는 ArchUnit 이 강제한다 (ADR-0008 · 절대 규칙 12).
// 모듈로 쪼개면 경계가 빌드 시스템에 박혀 실험 비용이 오르고,
// 이 프로젝트의 규모에서 얻는 것은 ArchUnit 이 이미 준다.
rootProject.name = "stay-inventory-sync"
