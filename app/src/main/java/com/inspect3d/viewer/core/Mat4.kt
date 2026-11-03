package com.inspect3d.viewer.core

/**
 * Helpers for the column-major 4x4 float matrices Filament's `TransformManager` expects
 * (`m[column * 4 + row]`, same convention as OpenGL).
 */
object Mat4 {

    fun identity() = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    fun translation(t: Vec3) = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        t.x, t.y, t.z, 1f,
    )

    /** Uniform scale followed by a translation. */
    fun scaleTranslation(scale: Float, t: Vec3) = floatArrayOf(
        scale, 0f, 0f, 0f,
        0f, scale, 0f, 0f,
        0f, 0f, scale, 0f,
        t.x, t.y, t.z, 1f,
    )

    /**
     * Builds a rigid transform whose local X, Y and Z axes are [right], [up] and [forward],
     * positioned at [origin]. Axes are expected to be orthonormal.
     */
    fun fromBasis(right: Vec3, up: Vec3, forward: Vec3, origin: Vec3, scale: Float = 1f) =
        floatArrayOf(
            right.x * scale, right.y * scale, right.z * scale, 0f,
            up.x * scale, up.y * scale, up.z * scale, 0f,
            forward.x * scale, forward.y * scale, forward.z * scale, 0f,
            origin.x, origin.y, origin.z, 1f,
        )

    /** Transforms a position by a column-major 4x4 matrix (w = 1). */
    fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray, outOffset: Int) {
        out[outOffset] = m[0] * x + m[4] * y + m[8] * z + m[12]
        out[outOffset + 1] = m[1] * x + m[5] * y + m[9] * z + m[13]
        out[outOffset + 2] = m[2] * x + m[6] * y + m[10] * z + m[14]
    }

    /** Transforms a direction by the upper-left 3x3 block (ignores translation). */
    fun transformDirection(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray, outOffset: Int) {
        out[outOffset] = m[0] * x + m[4] * y + m[8] * z
        out[outOffset + 1] = m[1] * x + m[5] * y + m[9] * z
        out[outOffset + 2] = m[2] * x + m[6] * y + m[10] * z
    }

    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (c in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) {
                    sum += a[k * 4 + row] * b[c * 4 + k]
                }
                r[c * 4 + row] = sum
            }
        }
        return r
    }

    /**
     * Inverse-transpose of the upper-left 3x3 block, which is what normals must be
     * transformed with when a node transform contains non-uniform scale.
     */
    fun normalMatrix(m: FloatArray): FloatArray {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[4]; val e = m[5]; val f = m[6]
        val g = m[8]; val h = m[9]; val i = m[10]

        val det = a * (e * i - f * h) - d * (b * i - c * h) + g * (b * f - c * e)
        if (det == 0f) return identity()
        val id = 1f / det

        // inverse(M3)^T, written straight into a column-major 4x4.
        val out = identity()
        out[0] = (e * i - f * h) * id
        out[4] = -(b * i - c * h) * id
        out[8] = (b * f - c * e) * id
        out[1] = -(d * i - f * g) * id
        out[5] = (a * i - c * g) * id
        out[9] = -(a * f - c * d) * id
        out[2] = (d * h - e * g) * id
        out[6] = -(a * h - b * g) * id
        out[10] = (a * e - b * d) * id

        // transpose the 3x3
        var t = out[1]; out[1] = out[4]; out[4] = t
        t = out[2]; out[2] = out[8]; out[8] = t
        t = out[6]; out[6] = out[9]; out[9] = t
        return out
    }

    /** Rotation around an arbitrary unit axis, in radians. */
    fun rotation(axis: Vec3, radians: Float): FloatArray {
        val c = kotlin.math.cos(radians)
        val s = kotlin.math.sin(radians)
        val t = 1f - c
        val (x, y, z) = axis.normalized()
        return floatArrayOf(
            t * x * x + c, t * x * y + s * z, t * x * z - s * y, 0f,
            t * x * y - s * z, t * y * y + c, t * y * z + s * x, 0f,
            t * x * z + s * y, t * y * z - s * x, t * z * z + c, 0f,
            0f, 0f, 0f, 1f,
        )
    }
}
