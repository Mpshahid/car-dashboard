package com.cardash.dashboard.domain

/** Unit conversions. The app defaults to UK units (mph, UK MPG, Celsius). */
object Units {
    const val PETROL_AFR = 14.7          // stoichiometric air-fuel ratio (petrol)
    const val PETROL_DENSITY_G_L = 745.0 // grams per litre

    fun kmhToMph(kmh: Double) = kmh * 0.621371
    fun kmToMiles(km: Double) = km * 0.621371
    fun cToF(c: Double) = c * 9.0 / 5.0 + 32.0
    fun litresToUkGallons(l: Double) = l / 4.54609

    /** Litres/hour of petrol burned for a given mass air-flow (g/s). */
    fun petrolLitresPerHour(mafGramsPerSec: Double): Double {
        val fuelGramsPerSec = mafGramsPerSec / PETROL_AFR
        return fuelGramsPerSec / PETROL_DENSITY_G_L * 3600.0
    }

    /** Instantaneous L/100km from air-flow and road speed. */
    fun petrolL100(mafGramsPerSec: Double, speedKmh: Double): Double {
        if (speedKmh < 1.0) return 0.0
        return petrolLitresPerHour(mafGramsPerSec) / speedKmh * 100.0
    }

    /** Convert L/100km to UK miles per gallon. */
    fun l100ToMpgUk(l100: Double): Double = if (l100 <= 0.0) 0.0 else 282.481 / l100
}
