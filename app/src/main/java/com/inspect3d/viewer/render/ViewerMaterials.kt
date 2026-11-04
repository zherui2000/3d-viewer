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

        /** World-space direction *towards* the Shaded mode's key light; set per frame. */
        const val PARAM_KEY_DIRECTION = "keyDirection"

        /** Fraction of the camera distance an overlay is drawn nearer the camera by. */
        const val PARAM_PULL_TO_CAMERA = "pullToCamera"

        private const val CLIP_VARIABLE = "clipDistance"

        /** Shared vertex stage: signed distance to the section plane, in object space. */
        private val CLIP_VERTEX_SHADER = """
            void materialVertex(inout MaterialVertexInputs material) {
                vec4 plane = materialParams.$PARAM_CLIP_PLANE;
                float signedDistance = dot(getPosition().xyz, plane.xyz) + plane.w;
                material.$CLIP_VARIABLE = vec4(signedDistance, 0.0, 0.0, 0.0);
            }
        """.trimIndent()

        /**
         * The overlay's vertex stage: the section-plane distance, plus a small pull towards
         * the camera. Edge and wireframe lines lie exactly on the surface they outline, so
         * the depth test between the two is a coin toss and the lines flicker. Moving the
         * lines a fraction of the camera distance nearer settles it at any zoom.
         *
         * The earlier fix pushed the *surface* back with a polygon offset instead. Its
         * slope term grows without bound on faces seen edge-on, which a dense organic
         * model is full of, and it pushed those faces behind the grid, so the grid showed
         * through the part. Moving the lines leaves the surface's depth alone.
         */
        private val OVERLAY_VERTEX_SHADER = """
            void materialVertex(inout MaterialVertexInputs material) {
                vec4 plane = materialParams.$PARAM_CLIP_PLANE;
                float signedDistance = dot(getPosition().xyz, plane.xyz) + plane.w;
                material.$CLIP_VARIABLE = vec4(signedDistance, 0.0, 0.0, 0.0);
                vec3 toCamera = getWorldCameraPosition() - material.worldPosition.xyz;
                material.worldPosition.xyz += toCamera * materialParams.$PARAM_PULL_TO_CAMERA;
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
         * The CAD-viewer look, as eDrawings shades a part: glossy plastic under a key light
         * from above and to the left of the viewer, a headlight so nothing the viewer can
         * see is left unlit, and an ambient floor so no face falls into black. No shadows
         * are cast. Unlit rather than a re-rigged lit material, so the result does not
         * depend on light intensities, exposure or the metallic slider.
         *
         * The specular highlights carry most of the shape. Matte (Lambert) light alone, at
         * any balance tried, read as "no shading" on a phone: on a light, finely bumpy
         * part its variation is soft and local, and the eye reads form from the bright
         * streaks where a surface turns towards the light. Those streaks are what made the
         * lit Solid mode look shaded, and what eDrawings' plastic look has too. The key
         * light's is broad and strong; a weaker headlight one lights the crown of every
         * bump facing the viewer.
         *
         * The normal is turned towards the viewer first: the material is double sided, and
         * once a section opens the part, the inside of the shell must light like the outside.
         */
        private val EVEN_FRAGMENT_SHADER = """
            void material(inout MaterialInputs material) {
                prepareMaterial(material);
                float cut = step(0.0, variable_$CLIP_VARIABLE.x) * materialParams.$PARAM_CLIP_ENABLED;

                vec3 v = normalize(getWorldViewVector());
                vec3 n = normalize(getWorldGeometricNormalVector());
                n = dot(n, v) < 0.0 ? -n : n;
                vec3 l = normalize(materialParams.$PARAM_KEY_DIRECTION);

                float key = max(dot(n, l), 0.0);
                float head = max(dot(n, v), 0.0);
                float keyGloss = pow(max(dot(n, normalize(l + v)), 0.0), $EVEN_KEY_SHININESS);
                float headGloss = pow(head, $EVEN_HEAD_SHININESS);

                float diffuse = $EVEN_AMBIENT + $EVEN_KEY * key + $EVEN_HEAD * head;
                float gloss = $EVEN_KEY_GLOSS * keyGloss + $EVEN_HEAD_GLOSS * headGloss;
                vec3 rgb = materialParams.$PARAM_BASE_COLOR * diffuse + vec3(gloss);
                material.baseColor = vec4(rgb, 1.0 - cut);
            }
        """.trimIndent()

        // Tuned on a Galaxy S25 Ultra against an organic 680 k-triangle part, with the view's
        // tone curve linear (see ViewerRenderer.linearGrading) so these numbers reach the
        // screen as written. Diffuse tops out at 0.85 of the surface colour, about 190/255
        // for the default Steel, and a face turned away from the key keeps 0.12-0.3, a
        // dark grey, never black. The highlights add up to 0.55 of white on top.
        private const val EVEN_AMBIENT = "0.12"
        private const val EVEN_KEY = "0.55"
        private const val EVEN_HEAD = "0.18"
        private const val EVEN_KEY_GLOSS = "0.45"
        private const val EVEN_KEY_SHININESS = "20.0"
        private const val EVEN_HEAD_GLOSS = "0.10"
        private const val EVEN_HEAD_SHININESS = "32.0"

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
         * Compiles and uploads the materials. With the full optimiser this takes most of a
         * second (855 ms measured on a Galaxy S25 Ultra), so call it while the "loading"
         * state is already on screen.
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
                    uniformParameter(MaterialBuilder.UniformType.FLOAT3, PARAM_KEY_DIRECTION)
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
                    uniformParameter(MaterialBuilder.UniformType.FLOAT, PARAM_PULL_TO_CAMERA)
                    materialVertex(OVERLAY_VERTEX_SHADER)
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
                // PERFORMANCE, not NONE. With NONE the GLSL handed to the driver still
                // carries raw preprocessor blocks and `#line` markers that rely on the
                // GL_GOOGLE_cpp_style_line_directive extension. The Adreno 830 driver in
                // the Galaxy S25 (0800.64.8) does not support it, rejects the shader as
                // "malformed preprocessor directive", and Filament aborts the process
                // when a program fails to link: that was the crash on launch. The full
                // optimiser round-trips through SPIR-V and emits plain, preprocessed GLSL,
                // the same path Filament's offline compiler `matc` uses by default.
                .optimization(MaterialBuilder.Optimization.PERFORMANCE)
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
