package com.inspect3d.viewer.parse

import android.util.Xml
import com.inspect3d.viewer.core.FloatList
import com.inspect3d.viewer.core.IntList
import com.inspect3d.viewer.core.Mat4
import com.inspect3d.viewer.model.ModelParseException
import com.inspect3d.viewer.model.RawMesh
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * 3MF reader. A 3MF file is an OPC (zip) package whose `3D/3dmodel.model` part holds the
 * geometry as XML: objects made of a vertex list plus a triangle list, assembled by a
 * `<build>` section (and optionally by `<components>`) through 3x4 affine transforms.
 *
 * Only the core specification is read — meshes, components, build items and the document
 * unit. Materials, colours, beam lattices and slice extensions are ignored, which is fine
 * for inspection: the viewer shades with its own material and measures in the document's
 * declared unit.
 */
object ThreeMfParser {

    private const val MAX_NESTING = 32

    private class MfObject(
        val vertices: FloatArray,
        val triangles: IntArray,
        val components: List<Component>,
    )

    private class Component(val objectId: String, val transform: FloatArray?)

    private class BuildItem(val objectId: String, val transform: FloatArray?)

    /**
     * [newParser] exists so tests can supply a JVM `XmlPullParser`; on device the default
     * uses the platform's. Nothing else about parsing changes.
     */
    fun parse(
        bytes: ByteArray,
        newParser: () -> XmlPullParser = { Xml.newPullParser() },
    ): RawMesh {
        val modelXml = extractModelPart(bytes)
            ?: throw ModelParseException("This 3MF package does not contain a 3D model part.")

        val objects = LinkedHashMap<String, MfObject>()
        val items = ArrayList<BuildItem>()
        var unit = "millimeter"

        val parser = newParser()
        parser.setInput(ByteArrayInputStream(modelXml), null)

        var currentId: String? = null
        var vertices = FloatList()
        var triangles = IntList()
        var components = ArrayList<Component>()
        var inMesh = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.substringAfterLast(':')) {
                    "model" -> parser.getAttributeValue(null, "unit")?.let { unit = it }

                    "object" -> {
                        currentId = parser.getAttributeValue(null, "id")
                        vertices = FloatList(1 shl 14)
                        triangles = IntList(1 shl 14)
                        components = ArrayList()
                    }

                    "mesh" -> inMesh = true

                    "vertex" -> if (inMesh) {
                        vertices.add(
                            parser.floatAttr("x"),
                            parser.floatAttr("y"),
                            parser.floatAttr("z"),
                        )
                    }

                    "triangle" -> if (inMesh) {
                        triangles.add(
                            parser.intAttr("v1"),
                            parser.intAttr("v2"),
                            parser.intAttr("v3"),
                        )
                    }

                    "component" -> {
                        val id = parser.getAttributeValue(null, "objectid")
                        if (id != null) {
                            components.add(Component(id, parseTransform(parser.getAttributeValue(null, "transform"))))
                        }
                    }

                    "item" -> {
                        val id = parser.getAttributeValue(null, "objectid")
                        if (id != null) {
                            items.add(BuildItem(id, parseTransform(parser.getAttributeValue(null, "transform"))))
                        }
                    }
                }

                XmlPullParser.END_TAG -> when (parser.name.substringAfterLast(':')) {
                    "mesh" -> inMesh = false
                    "object" -> {
                        val id = currentId
                        if (id != null) {
                            objects[id] = MfObject(vertices.toArray(), triangles.toArray(), components)
                        }
                        currentId = null
                    }
                }
            }
            event = parser.next()
        }

        if (objects.isEmpty()) {
            throw ModelParseException("This 3MF file contains no objects.")
        }

        // Some writers omit <build>; in that case show every object that is not already
        // instanced as a component of another object.
        val effectiveItems = items.ifEmpty {
            val referenced = objects.values.flatMap { it.components }.map { it.objectId }.toSet()
            objects.keys.filter { it !in referenced }.map { BuildItem(it, null) }
        }

        val positions = FloatList(1 shl 16)
        val indices = IntList(1 shl 16)
        var instanceCount = 0

        for (item in effectiveItems) {
            instanceCount += emit(objects, item.objectId, item.transform ?: Mat4.identity(), positions, indices, 0)
        }

        if (indices.size < 3) {
            throw ModelParseException("This 3MF file contains no triangles.")
        }

        return RawMesh(
            positions = positions.toArray(),
            normals = null, // 3MF stores no normals; flat shading is generated downstream.
            indices = indices.toArray(),
            unit = unit,
            objectCount = instanceCount.coerceAtLeast(1),
        )
    }

    /** Appends one object (and its components, recursively) under [transform]. */
    private fun emit(
        objects: Map<String, MfObject>,
        objectId: String,
        transform: FloatArray,
        positions: FloatList,
        indices: IntList,
        depth: Int,
    ): Int {
        if (depth > MAX_NESTING) return 0
        val obj = objects[objectId] ?: return 0
        var count = 0

        val vertexCount = obj.vertices.size / 3
        if (vertexCount > 0 && obj.triangles.isNotEmpty()) {
            val base = positions.size / 3
            val scratch = FloatArray(3)
            var i = 0
            while (i + 2 < obj.vertices.size) {
                Mat4.transformPoint(transform, obj.vertices[i], obj.vertices[i + 1], obj.vertices[i + 2], scratch, 0)
                positions.add(scratch[0], scratch[1], scratch[2])
                i += 3
            }
            var t = 0
            while (t + 2 < obj.triangles.size) {
                val a = obj.triangles[t]
                val b = obj.triangles[t + 1]
                val c = obj.triangles[t + 2]
                if (a in 0 until vertexCount && b in 0 until vertexCount && c in 0 until vertexCount) {
                    indices.add(base + a, base + b, base + c)
                }
                t += 3
            }
            count++
        }

        for (component in obj.components) {
            val childTransform = component.transform?.let { Mat4.multiply(transform, it) } ?: transform
            count += emit(objects, component.objectId, childTransform, positions, indices, depth + 1)
        }
        return count
    }

    /**
     * 3MF transforms are 12 numbers describing a row-major 4x3 affine matrix
     * (`m00 m01 m02 m10 … m30 m31 m32`), i.e. row-vector convention. Converted here into
     * the column-major 4x4 form the rest of the app uses.
     */
    private fun parseTransform(value: String?): FloatArray? {
        if (value.isNullOrBlank()) return null
        val parts = value.trim().split(Regex("\\s+"))
        if (parts.size < 12) return null
        val v = FloatArray(12) { parts[it].toFloatOrNull() ?: return null }
        return floatArrayOf(
            v[0], v[1], v[2], 0f,
            v[3], v[4], v[5], 0f,
            v[6], v[7], v[8], 0f,
            v[9], v[10], v[11], 1f,
        )
    }

    /** Reads the model part out of the zip container without unpacking it to disk. */
    private fun extractModelPart(bytes: ByteArray): ByteArray? {
        var fallback: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\', '/')
                if (!name.endsWith(".model", ignoreCase = true)) continue

                val content = zip.readEntryBytes()
                if (name.equals("3D/3dmodel.model", ignoreCase = true)) return content
                if (fallback == null) fallback = content
            }
        }
        return fallback
    }

    // Named so it cannot collide with InputStream.readAllBytes(), which is only available
    // from API 33 and would be picked over an extension at the call site.
    private fun ZipInputStream.readEntryBytes(): ByteArray {
        val out = ByteArrayOutputStream(1 shl 16)
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun XmlPullParser.floatAttr(name: String): Float =
        getAttributeValue(null, name)?.toFloatOrNull() ?: 0f

    private fun XmlPullParser.intAttr(name: String): Int =
        getAttributeValue(null, name)?.toIntOrNull() ?: -1
}
