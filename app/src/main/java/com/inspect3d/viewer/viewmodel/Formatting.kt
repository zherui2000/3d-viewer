package com.inspect3d.viewer.viewmodel

import com.inspect3d.viewer.core.Vec3
import com.inspect3d.viewer.model.LoadedModel
import java.util.Locale
import kotlin.math.abs

/**
 * Measurements are taken in render units and reported in the units of the source file,
 * which is the only number an engineer can act on. STL and OBJ carry no unit, so lengths
 * from those files are shown bare.
 */
fun unitSuffix(model: LoadedModel?): String = when (model?.unit?.lowercase(Locale.US)) {
    null -> ""
    "micron" -> "µm"
    "millimeter" -> "mm"
    "centimeter" -> "cm"
    "meter" -> "m"
    "inch" -> "in"
    "foot" -> "ft"
    else -> model.unit.orEmpty()
}

/** Formats a world-space length in the model's own units, e.g. `42.37 mm`. */
fun formatLength(model: LoadedModel?, worldLength: Float?): String {
    if (worldLength == null) return "—"
    val value = model?.toSourceLength(worldLength) ?: worldLength
    val suffix = unitSuffix(model)
    return if (suffix.isEmpty()) formatNumber(value) else "${formatNumber(value)} $suffix"
}

/** Formats a world-space offset (which may be negative) in the model's own units. */
fun formatSignedLength(model: LoadedModel?, worldLength: Float): String {
    val value = model?.toSourceLength(worldLength) ?: worldLength
    val sign = if (value >= 0f) "+" else "−"
    return "$sign${formatNumber(abs(value))}"
}

/** `120.0 × 80.0 × 15.0 mm` for the model info row. */
fun formatDimensions(model: LoadedModel): String {
    val size: Vec3 = model.sourceSize
    val suffix = unitSuffix(model)
    val body = "${formatNumber(size.x)} × ${formatNumber(size.y)} × ${formatNumber(size.z)}"
    return if (suffix.isEmpty()) body else "$body $suffix"
}

/** Decimal places that stay readable across the ranges CAD files actually use. */
fun formatNumber(value: Float): String {
    val magnitude = abs(value)
    val pattern = when {
        magnitude >= 10_000f -> "%.0f"
        magnitude >= 1_000f -> "%.1f"
        magnitude >= 10f -> "%.2f"
        magnitude >= 0.1f -> "%.3f"
        else -> "%.4f"
    }
    return String.format(Locale.US, pattern, value)
}

/** `1 284 903` triangles, grouped for legibility. */
fun formatCount(value: Int): String = String.format(Locale.US, "%,d", value)
