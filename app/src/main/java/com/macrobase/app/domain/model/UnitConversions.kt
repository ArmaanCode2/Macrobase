package com.macrobase.app.domain.model

/**
 * Centralized conversion utilities between Metric and Imperial measurement systems.
 */
object UnitConversions {
    // 1 kg = 2.20462262185 lb
    const val LBS_PER_KG = 2.20462262185
    // 1 in = 2.54 cm
    const val CM_PER_INCH = 2.54
    // 1 fl oz = 29.5735295625 mL
    const val ML_PER_FL_OZ = 29.5735295625

    fun kgToLbs(kg: Double): Double = kg * LBS_PER_KG
    fun lbsToKg(lbs: Double): Double = lbs / LBS_PER_KG

    fun cmToInches(cm: Double): Double = cm / CM_PER_INCH
    fun inchesToCm(inches: Double): Double = inches * CM_PER_INCH

    fun mlToFlOz(ml: Double): Double = ml / ML_PER_FL_OZ
    fun flOzToMl(flOz: Double): Double = flOz * ML_PER_FL_OZ
}
