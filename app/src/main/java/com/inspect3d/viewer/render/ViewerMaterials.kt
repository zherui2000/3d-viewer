package com.inspect3d.viewer.render

import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.filamat.MaterialBuilder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The four materials the viewer renders with, compiled on the device at start-up by
 * Filament's runtime material compiler (`filamat`). Compiling here rather than shipping
 * pre-built `.filamat` blobs keeps the clipping logic in this file, where it can be read
 * next to the code that feeds it, and removes the offline `matc` step from the build.
 *
 * ## How the cross-section is cut
 *
 * The section plane is applied in the **vertex** shader, which computes the signed
 * distance from each vertex to the plane and passes it down in a custom interpolant
 * (`clipDistance`). The fragment shader then drives `baseColor.a` from that distance and
 * lets `masked` blending throw the fragment away. Two details make this reliable:
 *
 *  * The distance is computed from `getPosition()` — **object space**. Filament's
 *    shader-side "world space" is translated for floating-point precision and is not
 *    guaranteed to match API-level world space, so it must not be used for plane maths.
 *    Because the fit transform is baked into the vertex data (see `LoadedModel.from`) and
 *    every renderable keeps an identity transform, object space *is* world space here.
 *  * Interpolating a signed distance across a triangle is exact (it is a linear function
 *    of position), so the cut edge is pixel-accurate rather than per-vertex.
 *
 * The model material is double sided, so the inside of the shell is lit and shaded
 * properly once the plane opens it up.
 */
class ViewerMaterials private constructor(
    /** Lit, double-sided, clippable: the model itself. */
    val model: Material,
    /**
     * Evenly lit, double-sided, clippable: the model in the eDrawings-style "Shaded"
     * modes, where no face may fall into shadow.
     */
    val even: Material,
    /** Unlit, clippable: wireframe, edges, grid, measurement markers and line. */
    val overlay: Material,
    /** Unlit, pre-multiplied transparent: the section plane visualisation. */
    val translucent: Material,
) {

    companion object {
        const val PARAM_BASE_COLOR = "baseColor"
        const val PARAM_METALLIC = "metallic"
        const val PARAM_ROUGHNESS = "roughness"
        const val PARAM_CLIP_PLANE = "clipPlane"
        const val PARAM_CLIP_ENABLED = "clipEnabled"

        private const val CLIP_VARIABLE = "clipDistance"

        /** Shared vertex stage: signed distance to the section plane, in object space. */
        private val CLIP_VERTEX_SHADER = """
            void materialVertex(inout MaterialVertexInputs material) {
                vec4 plane = materialParams.$PARAM_CLIP_PLANE;
                float signedDistance = dot(getPosition().xyz, plane.xyz) + plane.w;
                material.$CLIP_VARIABLE = vec4(signedDistance, 0.0, 0.0, 0.0);
            }
        """.trimIndent()

        private val MODEL_FRAGMENT_SHADER = """
            void material(inout MaterialInputs material) {
                prepareMaterial(material);
                material.baseColor.rgb = materialParams.$PARAM_BASE_COLOR;
                // Anything on the positive side of the plane is cut away. 'masked'
                // blending discards fragments whose alpha falls below the mask threshold.
                float cut = step(0.0, variable_$CLIP_VARIABLE.x) * materialParams.$PARAM_CLIP_ENABLED;
                material.baseColor.a = 1.0 - cut;
                material.metallic = materialParams.$PARAM_METALLIC;
                material.roughness = materialParams.$PARAM_ROUGHNESS;
            }
        """.trimIndent()

        /**
         * The CAD-viewer look: a headlight over a strong ambient term. Light always comes
         * from the viewer's side, so no face can turn away from it into shadow, while the
         * brightness still falls off with the angle to the eye enough for curvature and
         * steps to read. Unlit rather than a re-rigged lit material so the result does
         * not depend on light intensities, exposure or the metallic slider at all.
         *
         * `abs()` because the material is double sided: once a section opens the part,
         * the inside of the shell should read the same as the outside.
         */
        private val EVEN_FRAGMENT_SHADER = """
            void material(inout MaterialInputs material) {
                prepareMaterial(material);
                float cut = step(0.0, variable_$CLIP_VARIABLE.x) * materialParams.$PARAM_CLIP_ENABLED;
                float facing = abs(dot(getWorldGeometricNormalVector(), getWorldViewVector()));
                float light = $EVEN_AMBIENT + (1.0 - $EVEN_AMBIENT) * facing;
                material.baseColor = vec4(materialParams.$PARAM_BASE_COLOR * light, 1.0 - cut);
            }
        """.trimIndent()

        /** The floor of the even lighting: a face seen edge-on keeps this much colour. */
        private const val EVEN_AMBIENT = "0.62"

        private val OVERLAY_FRAGMENT_SHADER = """
            void material(inout MaterialInputs material) {
                prepareMaterial(material);
                float cut = step(0.0, variable_$CLIP_VARIABLE.x) * materialParams.$PARAM_CLIP_ENABLED;
                material.baseColor = vec4(materialParams.$PARAM_BASE_COLOR, 1.0 - cut);
            }
        """.trimIndent()

        private const val PLANE_VARIABLE = "planeCoord"

        /** Carries the quad's own ±1 coordinates down to the fragment stage. */
        private val PLANE_VERTEX_SHADER = """
            void materialVertex(inout MaterialVertexInputs material) {
                material.$PLANE_VARIABLE = vec4(getPosition().xy, 0.0, 0.0);
            }
        """.trimIndent()

        /**
         * The section plane indicator. A flat translucent sheet reads as a wall that hides
         * the part, so the fill fades out towards the edges and a bright rim marks the
         * plane's boundary — the eye gets the plane's position and orientation without
         * losing sight of the model behind it.
         */
        private val TRANSLUCENT_FRAGMENT_SHADER = """
            void material(inout MaterialInputs material) {
                prepareMaterial(material);
                vec4 color = materialParams.$PARAM_BASE_COLOR;

                // 0 at the centre of the quad, 1 at its border.
                vec2 local = abs(variable_$PLANE_VARIABLE.xy);
                float edge = max(local.x, local.y);

                float fill = color.a * (1.0 - smoothstep(0.60, 1.0, edge));
                // A thin, restrained edge: enough to read the plane's extent, not a frame.
                float rim = smoothstep(0.95, 0.98, edge) * (1.0 - smoothstep(0.99, 1.0, edge));

                float alpha = clamp(fill + rim * 0.28, 0.0, 1.0);
                vec3 rgb = mix(color.rgb, vec3(1.0), rim * 0.35);

                // 'transparent' blending expects pre-multiplied alpha.
                material.baseColor = vec4(rgb * alpha, alpha);
            }
        """.trimIndent()

        /**
         * Compiles and uploads the materials. Takes on the order of a hundred milliseconds
         * on first run, so call it while the "loading" state is already on screen.
         *
         * @throws IllegalStateException if a shader fails to compile, which can only
         *   happen if the shader sources above are edited into something invalid.
         */
        fun create(engine: Engine): ViewerMaterials {
            MaterialBuilder.init()
            try {
                val model = build(engine, "InspectModel") {
                    shading(MaterialBuilder.Shading.LIT)
                    blending(MaterialBuilder.BlendingMode.MASKED)
                    doubleSided(true)
                    require(MaterialBuilder.VertexAttribute.POSITION)
                    require(MaterialBuilder.VertexAttribute.TANGENTS)
                    variable(MaterialBuilder.Variable.CUSTOM0, CLIP_VARIABLE)
                    uniformParameter(MaterialBuilder.UniformType.FLOAT3, PARAM_BASE_COLOR)
                    uniformParameter(MaterialBuilder.UniformType.FLOAT, PARAM_METALLIC)
                    uniformParameter(MaterialBuilder.UniformType.FLOAT, PARAM_ROUGHNESS)
                    uniformParameter(MaterialBuilder.UniformType.FLOAT4, PARAM_CLIP_PLANE)
                    uniformParameter(MaterialBuilder.UniformType.FLOAT, PARAM_CLIP_ENABLED)
                    materialVertex(CLIP_VERTEX_SHADER)
                    material(MODEL_FRAGMENT_SHADER)
                }

                val even = build(engine, "InspectEven") {
                    shading(MaterialBuilder.Shading.UNLIT)
                    blending(MaterialBuilder.BlendingMode.MASKED)
                    doubleSided(true)
                    require(MaterialBuilder.VertexAttribute.POSITION)
                    // Tangents carry the normal; unlit materials do not ask for it otherwise.
                    require(MaterialBuilder.VertexAttribute.TANGENTS)
                    variable(MaterialBuilder.Variable.CUSTOM0, CLIP_VARIABLE)
                    uniformParameter(MaterialBuilder.UniformType.FLOAT3, PARAM_BASE_COLOR)
                    uniformParameter(MaterialBuilder.UniformType.FLOAT4, PARAM_CLIP_PLANE)
                    uniformParameter(MaterialBuilder.UniformType.FLOAT, PARAM_CLIP_ENABLED)
                    materialVertex(CLIP_VERTEX_SHADER)
                    material(EVEN_FRAGMENT_SHADER)
                }

                val overlay = build(engine, "InspectOverlay") {
                    shading(MaterialBuilder.Shading.UNLIT)
                    blending(MaterialBuilder.BlendingMode.MASKED)
                    doubleSided(true)
                    require(MaterialBuilder.VertexAttribute.POSITION)
                    require(MaterialBuilder.VertexAttribute.TANGENTS)
                    variable(MaterialBuilder.Variable.CUSTOM0, CLIP_VARIABLE)
                    uniformParameter(MaterialBuilder.UniformType.FLOAT3, PARAM_BASE_COLOR)
                    uniformParameter(MaterialBuilder.UniformType.FLOAT4, PARAM_CLIP_PLANE)
                    uniformParameter(MaterialBuilder.UniformType.FLOAT, PARAM_CLIP_ENABLED)
                    materialVertex(CLIP_VERTEX_SHADER)
                    material(OVERLAY_FRAGMENT_SHADER)
                }

                val translucent = build(engine, "InspectTranslucent") {
                    shading(MaterialBuilder.Shading.UNLIT)
                    blending(MaterialBuilder.BlendingMode.TRANSPARENT)
                    doubleSided(true)
                    require(MaterialBuilder.VertexAttribute.POSITION)
                    require(MaterialBuilder.VertexAttribute.TANGENTS)
                    variable(MaterialBuilder.Variable.CUSTOM0, PLANE_VARIABLE)
                    uniformParameter(MaterialBuilder.UniformType.FLOAT4, PARAM_BASE_COLOR)
                    materialVertex(PLANE_VERTEX_SHADER)
                    material(TRANSLUCENT_FRAGMENT_SHADER)
                }

                return ViewerMaterials(model, even, overlay, translucent)
            } finally {
                // Frees the compiler's native resources; the compiled materials stay valid.
                MaterialBuilder.shutdown()
            }
        }

        private inline fun build(
            engine: Engine,
            name: String,
            configure: MaterialBuilder.() -> Unit,
        ): Material {
            val builder = MaterialBuilder()
                .name(name)
                .platform(MaterialBuilder.Platform.MOBILE)
                .targetApi(MaterialBuilder.TargetApi.OPENGL)
                // NONE keeps start-up fast: the GLSL is passed through without running the
                // SPIR-V optimiser, which is what we want for four small shaders.
                .optimization(MaterialBuilder.Optimization.NONE)
            builder.configure()

            val result = builder.build()
            check(result.isValid) { "Failed to compile the '$name' material." }

            // The compiler hands back a heap ByteBuffer, but Filament reads payloads
            // through the JNI direct-buffer address. Copying into a direct buffer first
            // keeps this off any path where that address would be null.
            val compiled = result.buffer
            val payload = ByteBuffer
                .allocateDirect(compiled.remaining())
                .order(ByteOrder.nativeOrder())
            payload.put(compiled)
            payload.flip()

            return Material.Builder()
                .payload(payload, payload.remaining())
                .build(engine)
        }
    }
}
