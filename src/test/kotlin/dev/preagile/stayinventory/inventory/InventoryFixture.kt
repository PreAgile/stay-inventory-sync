package dev.preagile.stayinventory.inventory

import java.sql.Connection
import java.time.LocalDate
import javax.sql.DataSource

/**
 * 재고 격자를 까는 픽스처.
 *
 * 리포지토리가 아니라 raw SQL 로 만든다. 픽스처가 프로덕션 코드를 거치면
 * "차감 로직으로 만든 상태를 차감 로직으로 검증" 하게 되어, 로직이 틀렸을 때
 * 픽스처도 같이 틀려 테스트가 통과한다.
 */
class InventoryFixture(private val dataSource: DataSource) {

    fun wipe() = exec(
        """
        TRUNCATE channel_policy, inventory_hold, inbound_message, outbox_event,
                 reservation, daily_inventory, room_type, property
        RESTART IDENTITY CASCADE
        """.trimIndent(),
    )

    /** 숙소·룸타입 하나와 [from] 부터 [days] 일치의 격자를 만든다. 룸타입 id 를 준다. */
    fun seedGrid(
        from: LocalDate,
        days: Int,
        physicalTotal: Int,
        overbookingLimit: Int = 0,
    ): Long = dataSource.connection.use { conn ->
        conn.exec("INSERT INTO property (name) VALUES ('어반스테이 성수')")
        conn.exec(
            "INSERT INTO room_type (property_id, name, capacity) " +
                "SELECT id, '디럭스', 2 FROM property ORDER BY id DESC LIMIT 1",
        )
        val roomTypeId = conn.queryLong("SELECT max(id) FROM room_type")
        repeat(days) { offset ->
            conn.exec(
                """
                INSERT INTO daily_inventory
                       (room_type_id, stay_date, physical_total, overbooking_limit, sold)
                VALUES ($roomTypeId, DATE '${from.plusDays(offset.toLong())}',
                        $physicalTotal, $overbookingLimit, 0)
                """.trimIndent(),
            )
        }
        roomTypeId
    }

    /**
     * 그 날짜를 매진으로 만든다.
     *
     * `sold` 만 올리면 `INV-2` 가 깨진다 -- 카운터가 예약 사실보다 크다. 훅이
     * 그것을 잡으므로 **점유 예약을 함께 만든다.** 픽스처가 불변식을 지켜야
     * 테스트 실패가 진짜 실패가 된다.
     */
    fun markSoldOut(roomTypeId: Long, stayDate: LocalDate) = dataSource.connection.use { conn ->
        val total = conn.queryLong(
            "SELECT physical_total + overbooking_limit FROM daily_inventory " +
                "WHERE room_type_id = $roomTypeId AND stay_date = DATE '$stayDate'",
        )
        conn.exec(
            """
            INSERT INTO reservation
                   (room_type_id, check_in, check_out, status, room_count,
                    channel, channel_reservation_id, guest_name)
            VALUES ($roomTypeId, DATE '$stayDate', DATE '${stayDate.plusDays(1)}',
                    'CONFIRMED', $total, 'FIXTURE', 'sold-out-$roomTypeId-$stayDate', '기존손님')
            """.trimIndent(),
        )
        conn.exec(
            "UPDATE daily_inventory SET sold = $total " +
                "WHERE room_type_id = $roomTypeId AND stay_date = DATE '$stayDate'",
        )
    }

    /**
     * 상태를 지정해 예약을 직접 넣는다.
     *
     * 서비스로는 `CONFIRMED` 밖에 만들 수 없다. 복원이 **`CONFIRMED` 에서만**
     * 일어난다는 비대칭을 증명하려면 나머지 여덟 상태를 만들 수단이 필요하다.
     */
    fun insertReservation(
        roomTypeId: Long,
        checkIn: LocalDate,
        checkOut: LocalDate,
        status: dev.preagile.stayinventory.domain.ReservationStatus,
        roomCount: Int,
    ): Long = dataSource.connection.use { conn ->
        conn.exec(
            """
            INSERT INTO reservation
                   (room_type_id, check_in, check_out, status, room_count,
                    channel, channel_reservation_id, guest_name)
            VALUES ($roomTypeId, DATE '$checkIn', DATE '$checkOut', '$status', $roomCount,
                    'FIXTURE', 'fx-${'$'}{java.util.UUID.randomUUID()}', '픽스처손님')
            """.trimIndent(),
        )
        conn.queryLong("SELECT max(id) FROM reservation")
    }

    /**
     * `sold` 를 직접 맞춘다. 점유 예약을 이미 넣어 둔 뒤에만 쓴다 --
     * 그렇지 않으면 `INV-2` 가 깨지고 훅이 그 테스트를 실패로 만든다.
     */
    fun forceSold(roomTypeId: Long, stayDate: LocalDate, sold: Int) = dataSource.connection.use {
        it.exec(
            "UPDATE daily_inventory SET sold = $sold " +
                "WHERE room_type_id = $roomTypeId AND stay_date = DATE '$stayDate'",
        )
    }

    fun sold(roomTypeId: Long, stayDate: LocalDate): Int = dataSource.connection.use { conn ->
        conn.queryLong(
            "SELECT sold FROM daily_inventory " +
                "WHERE room_type_id = $roomTypeId AND stay_date = DATE '$stayDate'",
        ).toInt()
    }

    private fun exec(sql: String) = dataSource.connection.use { it.exec(sql) }

    private fun Connection.exec(sql: String) = createStatement().use { it.executeUpdate(sql) }

    private fun Connection.queryLong(sql: String): Long =
        createStatement().use { st -> st.executeQuery(sql).use { it.next(); it.getLong(1) } }
}
