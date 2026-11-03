package com.inspect3d.viewer.render

import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.VertexBuffer
import com.inspect3d.viewer.core.TangentFrame
import com.inspect3d.viewer.model.LoadedModel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

/**
 * A pair of GPU buffers plus the number of indices to draw. Owned by the renderer, which
 * destroys them when a new model is loaded.
 */
class MeshBuffers(
    val vertices: VertexBuffer,
    val indices: IndexBuffer,
    val indexCount: Int,
) {
    fun destroy(engine: Engine) {
        engine.destroyVertexBuffer(vertices)
        engine.destroyIndexBuffer(indices)
    }
}

/**
 * Builds Filament vertex/index buffers for the loaded model and for the viewer's own
 * helper geometry (grid, measurement markers, measurement line, section plane).
 *
 * Every buffer uses the same two-stream layout â€” `POSITION` as `FLOAT3` in buffer 0 and
 * `TANGENTS` as `FLOAT4` in buffer 1 â€” so all three materials can be used with any of
 * these meshes.
 */
object GeometryFactory {

    /** Beyond this, a line-primitive wireframe costs more memory than it is worth. */
    const val MAX_WIREFRAME_TRIANGLES = 1_500_000

    fun buildModel(engine: Engine, model: LoadedModel): MeshBuffers =
        build(engine, model.positions, model.normals, model.indices)

    /**
     * Wireframe index buffer: three line segments per triangle, sharing the model's
     * vertex buffer. Duplicate interior edges are drawn twice rather than de-duplicated â€”
     * a hash set over millions of edges would cost far more than the overdraw does.
     */
    fun buildWireframeIndices(engine: Engine, model: LoadedModel): IndexBuffer? {
        val triangleCount = model.triangleCount
        if (triangleCount == 0 || triangleCount > MAX_WIREFRAME_TRIANGLES) return null

        val indices = model.indices
        val data = ByteBuffer
            .allocateDirect(triangleCount * 6 * 4)
            .order(ByteOrder.nativeOrder())

        for (t in 0 until triangleCount) {
            val a = indices[t * 3]
            val b = indices[t * 3 + 1]
            val c = indices[t * 3 + 2]
            data.putInt(a).putInt(b)
            data.putInt(b).putInt(c)
            data.putInt(c).putInt(a)
        }
        data.flip()

        val buffer = IndexBuffer.Builder()
            .indexCount(triangleCount * 6)
            .bufferType(IndexBuffer.Builder.IndexType.UINT)
            .build(engine)
        buffer.setBuffer(engine, data)
        return buffer
    }

    /** Unit-radius sphere, used for the measurement markers. */
    fun buildSphere(engine: Engine, rings: Int = 12, sectors: Int = 20): MeshBuffers {
        val positions = ArrayList<Float>((rings + 1) * (sectors + 1) * 3)
        val normals = ArrayList<Float>((rings + 1) * (sectors + 1) * 3)
        val indices = ArrayList<Int>(rings * sectors * 6)

        for (r in 0..rings) {
            val phi = Math.PI * r / rings
            val y = cos(phi).toFloat()
            val ringRadius = sin(phi).toFloat()
            for (s in 0..sectors) {
                val theta = 2.0 * Math.PI * s / sectors
                val x = (ringRadius * cos(theta)).toFloat()
                val z = (ringRadius * sin(theta)).toFloat()
                positions.add(x); positions.add(y); positions.add(z)
                normals.add(x); normals.add(y); normals.add(z)
            }
        }
        for (r in 0 until rings) {
            for (s in 0 until sectors) {
                val i0 = r * (sectors + 1) + s
                val i1 = i0 + sectors + 1
                indices.add(i0); indices.add(i1); indices.add(i0 + 1)
                indices.add(i0 + 1); indices.add(i1); indices.add(i1 + 1)
            }
        }

        return build(engine, positions.toFloatArray(), normals.toFloatArray(), indices.toIntArray())
    }

    /** Unit quad in the XY plane, used to visualise the section plane. */
    fun buildQuad(engine: Engine): MeshBuffers {
        val positions = floatArrayOf(
            -1f, -1f, 0f,
            1f, -1f, 0f,
            1f, 1f, 0f,
            -1f, 1f, 0f,
        )
        val normals = floatArrayOf(
            0f, 0f, 1f,
            0f, 0f, 1f,
            0f, 0f, 1f,
            0f, 0f, 1f,
        )
        val indices = intArrayOf(0, 1, 2, 0, 2, 3)
        return build(engine, positions, normals, indices)
    }

    /**
     * Ground grid drawn with `LINES`, spanning [-extent, extent] in X and Z at height [y].
     * The model is normalised so its largest half-extent is 1, which makes a fixed grid
     * size meaningful for every file.
     */
    fun buildGrid(engine: Engine, extent: Float = 2f, divisions: Int = 20, y: Float = -1.001f): MeshBuffers {
        val lineCount = (divisions + 1) * 2
        val positions = FloatArray(lineCount * 2 * 3)
        val normals = FloatArray(lineCount * 2 * 3)
        val indices = IntArray(lineCount * 2) { it }

        var p = 0
        val step = extent * 2f / divisions
        for (i in 0..divisions) {
            val offset = -extent + step * i
            // Line along Z
            positions[p] = offset; positions[p + 1] = y; positions[p + 2] = -extent
            positions[p + 3] = offset; positions[p + 4] = y; positions[p + 5] = extent
            p += 6
            // Line along X
            positions[p] = -extent; positions[p + 1] = y; positions[p + 2] = offset
            positions[p + 3] = extent; positions[p + 4] = y; positions[p + 5] = offset
            p += 6
        }
        for (i in normals.indices step 3) {
            normals[i] = 0f; normals[i + 1] = 1f; normals[i + 2] = 0f
        }

        return build(engine, positions, normals, indices)
    }

    /**
     * Open tube of unit radius running from the origin along +Z to z = 1, used to draw the
     * measurement line. A real tube is used rather than a `LINES` primitive because GL
     * lines are one physical pixel wide, which is nearly invisible on a phone display; the
     * renderer stretches this mesh between the two measured points.
     */
    fun buildTube(engine: Engine, sides: Int = 12): MeshBuffers {
        val positions = FloatArray((sides + 1) * 2 * 3)
        val normals = FloatArray((sides + 1) * 2 * 3)
        val indices = IntArray(sides * 6)

        for (s in 0..sides) {
            val angle = 2.0 * Math.PI * s / sides
            val x = cos(angle).toFloat()
            val y = sin(angle).toFloat()
            val base = s * 6
            positions[base] = x; positions[base + 1] = y; positions[base + 2] = 0f
            positions[base + 3] = x; positions[base + 4] = y; positions[base + 5] = 1f
            normals[base] = x; normals[base + 1] = y; normals[base + 2] = 0f
            normals[base + 3] = x; normals[base + 4] = y; normals[base + 5] = 0f
        }
        for (s in 0 until sides) {
            val i0 = s * 2
            val i1 = i0 + 1
            val i2 = i0 + 2
            val i3 = i0 + 3
            val at = s * 6
            indices[at] = i0; indices[at + 1] = i1; indices[at + 2] = i2
            indices[at + 3] = i2; indices[at + 4] = i1; indices[at + 5] = i3
        }

        return build(engine, positions, normals, indices)
    }

    /**
     * Uploads interleaved positions and normals into the viewer's standard layout.
     *
     * Filament does not consume raw normals: it expects a quaternion per vertex that
     * encodes the whole tangent frame (`TANGENTS`), from which the shader recovers the
     * normal. [packTangentFrame] builds that quaternion.
     */
    fun build(
        engine: Engine,
        positions: FloatArray,
        normals: FloatArray,
        indices: IntArray,
    ): MeshBuffers {
        val vertexCount = positions.size / 3

        val positionData = ByteBuffer
            .allocateDirect(vertexCount * 3 * 4)
            .order(ByteOrder.nativeOrder())
        positionData.asFloatBuffer().put(positions, 0, vertexCount * 3)

        val tangentData = ByteBuffer
            .allocateDirect(vertexCount * 4 * 4)
            .order(ByteOrder.nativeOrder())
        val tangents = tangentData.asFloatBuffer()
        val quaternion = FloatArray(4)
        for (v in 0 until vertexCount) {
            TangentFrame.pack(
                normals.getOrElse(v * 3) { 0f },
                normals.getOrElse(v * 3 + 1) { 1f },
                normals.getOrElse(v * 3 + 2) { 0f },
                quaternion,
            )
            tangents.put(quaternion)
        }

        val vertexBuffer = VertexBuffer.Builder()
            .vertexCount(vertexCount)
            .bufferCount(2)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 1, VertexBuffer.AttributeType.FLOAT4, 0, 16)
            .build(engine)
        vertexBuffer.setBufferAt(engine, 0, positionData)
        vertexBuffer.setBufferAt(engine, 1, tangentData)

        val indexData = ByteBuffer
            .allocateDirect(indices.size * 4)
            .order(ByteOrder.nativeOrder())
        indexData.asIntBuffer().put(indices)

        val indexBuffer = IndexBuffer.Builder()
            .indexCount(indices.size)
            .bufferType(IndexBuffer.Builder.IndexType.UINT)
            .build(engine)
        indexBuffer.setBuffer(engine, indexData)

        return MeshBuffers(vertexBuffer, indexBuffer, indices.size)
    }

}
