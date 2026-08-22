package dev.preagile.stayinventory.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * 숙소.
 *
 * `timezone` 이 값을 하는 자리는 날짜 해석이다. 체크인·체크아웃은 숙소의 현지
 * 시각으로 읽는다. UTC 로 저장하면 "3월 1일 재고" 가 숙소에 따라 다른 날을 뜻한다.
 */
@Entity
@Table(name = "property")
class Property(
    @Column(name = "name", nullable = false, length = 200)
    val name: String,

    @Column(name = "timezone", nullable = false, length = 64)
    val timezone: String = "Asia/Seoul",

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is Property && id != null && id == other.id)

    override fun hashCode(): Int = javaClass.hashCode()
}
