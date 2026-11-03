package com.inspect3d.viewer.model

/** Formats the viewer can parse. */
enum class ModelFormat(val label: String, val extensions: List<String>) {
    STL("STL", listOf("stl")),
    OBJ("OBJ", listOf("obj")),
    THREE_MF("3MF", listOf("3mf")),
    GLTF("glTF", listOf("gltf", "glb")),
    ;

    companion object {
        /** Mime types accepted by the Storage Access Framework picker. */
        val PICKER_MIME_TYPES = arrayOf(
            "model/stl",
            "model/obj",
            "model/3mf",
            "model/gltf+json",
            "model/gltf-binary",
            "application/octet-stream",
            "application/sla",
            "text/plain",
            "*/*",
        )

        fun fromFileName(fileName: String?): ModelFormat? {
            val ext = fileName?.substringAfterLast('.', "")?.lowercase() ?: return null
            return ModelFormat.entries.firstOrNull { ext in it.extensions }
        }

        val supportedExtensionsLabel: String =
            ModelFormat.entries.flatMap { it.extensions }.joinToString(", ") { ".$it" }
    }
}

/** Raised for anything the user should see as "this file cannot be opened". */
class ModelParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
