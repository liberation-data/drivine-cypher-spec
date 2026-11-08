package org.drivine.cypher.store

import org.drivine.DrivineInternalApi
import kotlinx.datetime.LocalDateTime

/**
 * A moment as SQLite keeps one, `uuuu-MM-dd'T'HH:mm:ss.SSSSSS`, without java.time: drivine4j's
 * `org.drivine.backend.sqlite.SqliteTimestamps.write` calls it.
 */
@DrivineInternalApi
object SqliteTimestamps {
    fun write(clock: LocalDateTime): String {
        fun two(n: Int) = n.toString().padStart(2, '0')
        // `uuuu`: four digits at least, a sign before 0 and past 9999.
        val year = if (clock.year < 0) "-" + (-clock.year).toString().padStart(4, '0') else (if (clock.year > 9999) "+" else "") + clock.year.toString().padStart(4, '0')
        return "$year-${two(clock.month.ordinal + 1)}-${two(clock.day)}T${two(clock.hour)}:${two(clock.minute)}:${two(clock.second)}." +
            (clock.nanosecond / 1000).toString().padStart(6, '0')
    }
}
