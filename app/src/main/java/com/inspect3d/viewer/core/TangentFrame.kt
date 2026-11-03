package com.inspect3d.viewer.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Encodes a surface normal as the quaternion Filament's `TANGENTS` vertex attribute
 * expects: the rotation taking (1,0,0) to the tangent, (0,1,0) to the bitangent and
 * (0,0,1) to the normal.
 *
 * This lives apart from the renderer on purpose. It is hand-written
 * matrix-to-quaternion maths — the kind that fails silently as strange shading rather
 * than as a crash — and keeping it free of Filament types means it can be unit tested on
 * the JVM by rotating (0,0,1) back and checking the normal comes out again.
 */
object TangentFrame {

    /** Writes the tangent-frame quaternion for a normal as (x, y, z, w) into [out]. */
    fun pack(nx: Float, ny: Float, nz: Float, out: FloatArray) {
        var x = nx
        var y = ny
        var z = nz
        val length = sqrt(x * x + y * y + z * z)
        if (length > 1e-20f) {
            x /= length; y /= length; z /= length
        } else {
            x = 0f; y = 1f; z = 0f
        }

        // Any vector not parallel to the normal gives a usable tangent; without texture
        // coordinates one choice is as good as another, as long as it is deterministic.
        val hx: Float
        val hy: Float
        val hz: Float
        if (abs(x) < 0.9f) {
            hx = 1f; hy = 0f; hz = 0f
        } else {
            hx = 0f; hy = 1f; hz = 0f
        }

        // tangent = normalize(helper x normal), bitangent = normal x tangent
        var tx = hy * z - hz * y
        var ty = hz * x - hx * z
        var tz = hx * y - hy * x
        val tLength = sqrt(tx * tx + ty * ty + tz * tz)
        if (tLength > 1e-20f) {
            tx /= tLength; ty /= tLength; tz /= tLength
        } else {
            tx = 1f; ty = 0f; tz = 0f
        }
        val bx = y * tz - z * ty
        val by = z * tx - x * tz
        val bz = x * ty - y * tx

        // Rotation matrix whose columns are (tangent, bitangent, normal), written out as
        // r<row><column> so the extraction below reads like the textbook form.
        val r00 = tx; val r01 = bx; val r02 = x
        val r10 = ty; val r11 = by; val r12 = y
        val r20 = tz; val r21 = bz; val r22 = z

        val trace = r00 + r11 + r22
        if (trace > 0f) {
            val s = sqrt(trace + 1f) * 2f
            out[0] = (r21 - r12) / s
            out[1] = (r02 - r20) / s
            out[2] = (r10 - r01) / s
            out[3] = 0.25f * s
        } else if (r00 > r11 && r00 > r22) {
            val s = sqrt(1f + r00 - r11 - r22) * 2f
            out[0] = 0.25f * s
            out[1] = (r01 + r10) / s
            out[2] = (r02 + r20) / s
            out[3] = (r21 - r12) / s
        } else if (r11 > r22) {
            val s = sqrt(1f + r11 - r00 - r22) * 2f
            out[0] = (r01 + r10) / s
            out[1] = 0.25f * s
            out[2] = (r12 + r21) / s
            out[3] = (r02 - r20) / s
        } else {
            val s = sqrt(1f + r22 - r00 - r11) * 2f
            out[0] = (r02 + r20) / s
            out[1] = (r12 + r21) / s
            out[2] = 0.25f * s
            out[3] = (r10 - r01) / s
        }

        // Filament reads w's sign as the frame's handedness; keep it positive.
        if (out[3] < 0f) {
            out[0] = -out[0]; out[1] = -out[1]; out[2] = -out[2]; out[3] = -out[3]
        }
    }

    /**
     * Rotates (0,0,1) by the quaternion — the same thing the shader does to recover the
     * normal. Exists so the encoding can be checked against its own inverse.
     */
    fun normalFrom(q: FloatArray): Vec3 {
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        return Vec3(
            2f * (x * z + y * w),
            2f * (y * z - x * w),
            1f - 2f * (x * x + y * y),
        )
    }
}
