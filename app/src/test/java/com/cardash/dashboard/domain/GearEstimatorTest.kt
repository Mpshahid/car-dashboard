package com.cardash.dashboard.domain

import com.cardash.dashboard.obd.GearReading
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Situations from the Meriva's own drive log (one reading a second, km/h to rpm), with the gear the dashboard should
 * show after each reading ("N" = neutral or clutch down).
 */
class GearEstimatorTest {

    private fun run(vararg readings: Pair<Int, Int>): String {
        val est = GearEstimator()
        repeat(30) { est.estimate(0.0, 800.0 + it % 3, it * 1000L) }          // warm idle at the lights
        return readings.mapIndexed { i, (kmh, rpm) ->
            when (val g = est.estimate(kmh.toDouble(), rpm.toDouble(), 30_000L + i * 1000L)) {
                is GearReading.Engaged -> g.gear.toString()
                else -> "N"
            }
        }.joinToString(" ")
    }

    @Test fun cruisingInFifth() {
        assertEquals("5 5 5 5", run(97 to 2460, 98 to 2486, 99 to 2511, 100 to 2536))
    }

    @Test fun rollingInNeutralAtIdleIsNotASixthGear() {
        // About 1,000 rpm at 48-51 km/h with the clutch down: the old six-speed table called this 6th gear.
        assertEquals("N N N N N N", run(48 to 935, 49 to 954, 49 to 960, 48 to 981, 49 to 995, 51 to 959))
    }

    @Test fun coastingToAStopWithTheClutchDown() {
        // 4th gear, clutch down at 46 km/h, then the engine idles while the car slows (this used to count 5-6-5-4).
        assertEquals(
            "4 4 4 4 N N N N N N N",
            run(46 to 1464, 46 to 1476, 46 to 1470, 46 to 1109, 46 to 1053, 42 to 927, 41 to 921, 40 to 929, 38 to 908,
                37 to 903, 35 to 905),
        )
    }

    @Test fun pullingAwayShowsFirstThenSecond() {
        // Clutch slipping, 1st for about two seconds, a gear change, 2nd.
        assertEquals("N N 1 1 2 2", run(2 to 975, 4 to 750, 13 to 1717, 17 to 1932, 24 to 1604, 31 to 2082))
    }

    @Test fun clutchGoingDownDoesNotFlashTheNextGearUp() {
        // In 4th; as the clutch goes down the rpm falls through 5th's figure on one reading.
        assertEquals("4 4 4 4 N N N", run(44 to 1400, 45 to 1440, 44 to 1410, 46 to 1155, 47 to 973, 48 to 935, 49 to 954))
    }

    @Test fun changingUp() {
        assertEquals("3 3 3 4 4", run(40 to 1790, 42 to 1880, 44 to 1300, 45 to 1435, 47 to 1500))
    }

    @Test fun theSameReadingAgainIsNotASecondSighting() {
        // A poll that didn't refresh speed and RPM repeats them: still one reading.
        assertEquals("4 4 4 4 4", run(45 to 1440, 45 to 1442, 44 to 1410, 44 to 1410, 44 to 1408))
    }
}
