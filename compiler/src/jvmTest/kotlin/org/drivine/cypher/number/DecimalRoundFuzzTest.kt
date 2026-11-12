package org.drivine.cypher.number

import org.drivine.cypher.sql.RoundingMode
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.random.Random
import kotlin.test.assertEquals

/** [DecimalRound] beside Java's `BigDecimal`, which drivine4j's drivine_round calls, on doubles and whole numbers of every size. */
class DecimalRoundFuzzTest {

    private val modes = RoundingMode.entries - RoundingMode.UNNECESSARY

    private fun java(number: BigDecimal, places: Long, mode: RoundingMode): Double =
        if (places >= number.scale()) number.toDouble() else number.setScale(places.toInt(), java.math.RoundingMode.valueOf(mode.name)).toDouble()

    @Test
    fun `it rounds as BigDecimal does`() {
        val random = Random(108)
        repeat(200_000) {
            val x = when (random.nextInt(4)) {
                0 -> Double.fromBits(random.nextLong()).takeIf { it.isFinite() } ?: 1.5
                1 -> random.nextInt(-100_000, 100_000) / 1000.0
                2 -> (random.nextInt(-2000, 2000) + 0.5) / 10.0.pow(random.nextInt(0, 4))
                else -> random.nextDouble(-1e6, 1e6)
            }
            val places = random.nextLong(-20, 25)
            val mode = modes.random(random)
            assertEquals(java(BigDecimal.valueOf(x), places, mode), DecimalRound.of(x, places, mode), "round($x, $places, $mode)")
            val n = if (random.nextBoolean()) random.nextLong() else random.nextLong(-10_000, 10_000)
            assertEquals(java(BigDecimal.valueOf(n), places, mode), DecimalRound.of(n, places, mode), "round($n, $places, $mode)")
        }
    }

    private fun Double.pow(n: Int) = Math.pow(this, n.toDouble())
}
