package com.inspect3d.viewer.core

import kotlin.math.abs
import kotlin.math.sqrt

/** Immutable 3-component vector. Used for camera state, picking results and plane math. */
data class Vec3(val x: Float, val y: Float, val z: Float) {

    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    infix fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z

    infix fun cross(o: Vec3) = Vec3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x,
    )

    val length: Float get() = sqrt(x * x + y * y + z * z)

    fun normalized(): Vec3 {
        val l = length
        return if (l < 1e-20f) ZERO else Vec3(x / l, y / l, z / l)
    }

    /** Any unit vector perpendicular to this one. */
    fun anyPerpendicular(): Vec3 {
        val helper = if (abs(x) < 0.9f) UNIT_X else UNIT_Y
        return (helper cross this).normalized()
    }

    fun toFloatArray() = floatArrayOf(x, y, z)

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val UNIT_X = Vec3(1f, 0f, 0f)
        val UNIT_Y = Vec3(0f, 1f, 0f)
        val UNIT_Z = Vec3(0f, 0f, 1f)
    }
}

fun distance(a: Vec3, b: Vec3): Float = (a - b).length
