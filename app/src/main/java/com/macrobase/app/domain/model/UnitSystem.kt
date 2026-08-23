package com.macrobase.app.domain.model

/**
 * Standard unit systems for measurement in the application.
 */
enum class UnitSystem(val displayName: String) {
    METRIC("Metric (kg, cm, mL)"),
    IMPERIAL("Imperial (lbs, ft/in, fl oz)")
}
