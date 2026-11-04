package com.inspect3d.viewer.render

import android.content.Context
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import com.google.android.filament.Box
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.inspect3d.viewer.core.Mat4
import com.inspect3d.viewer.core.Vec3
import com.inspect3d.viewer.model.FeatureEdges
import com.inspect3d.viewer.model.LoadedModel
import com.inspect3d.viewer.viewmodel.AppearanceState
import com.inspect3d.viewer.viewmodel.SectionState
import com.inspect3d.viewer.viewmodel.ViewerUiState
import kotlin.math.abs
import kotlin.math.pow

/**
 * Owns the Filament engine and the scene, and drives one render per display frame.
 *
 * The renderer is deliberately a *consumer* of [ViewerUiState]: every frame it reads the
 * current state through [stateProvider] and reconciles the scene with it. That keeps the
 * ViewModel as the single source of truth without pushing a recomposition through Compose
 * for every finger movement, and it means any state change — a slider, a colour, a new
 * model — takes effect on the next frame with no extra plumbing.
 *
 * All Filament calls happen on the thread that drives the Choreographer (the main thread),
 * which is the engine's required usage pattern.
 */
class ViewerRenderer(context: Context) {

    /** Reads the state to render. Set by the Compose layer right after construction. */
    var stateProvider: (() -> ViewerUiState)? = null

    /** Transient messages for the UI (e.g. a limit that was hit). */
    var onNotice: ((String) -> Unit)? = null

    /** Reports surface size changes so the camera can frame models to the viewport. */
    var onViewportResized: ((width: Int, height: Int) -> Unit)? = null

    /** Non-null if the engine or its shaders could not be set up on this device. */
    val initializationError: String?

    /** Current surface size in pixels; the UI needs it to scale pan gestures. */
    val viewportWidthPixels: Int get() = viewportWidth
    val viewportHeightPixels: Int get() = viewportHeight

    private val choreographer = Choreographer.getInstance()
    private val displayHelper = DisplayHelper(context)
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)

    private val engine = Engine.create()
    private val renderer = engine.createRenderer()
    private val scene = engine.createScene()
    private val view = engine.createView()
    private val entityManager = EntityManager.get()
    private val transformManager = engine.transformManager
    private val lightManager = engine.lightManager

    private val cameraEntity = entityManager.create()
    private val camera = engine.createCamera(cameraEntity)

    private val materials: ViewerMaterials?
    private var modelMaterial: MaterialInstance? = null
    private var wireMaterial: MaterialInstance? = null
    private var edgeMaterial: MaterialInstance? = null
    private var gridMaterial: MaterialInstance? = null
    private var planeMaterial: MaterialInstance? = null
    private var markerAMaterial: MaterialInstance? = null
    private var markerBMaterial: MaterialInstance? = null
    private var lineMaterial: MaterialInstance? = null

    private val skybox: Skybox

    // Helper geometry, built once and reused for every model.
    private val sphereMesh: MeshBuffers
    private val tubeMesh: MeshBuffers
    private val quadMesh: MeshBuffers
    private val gridMesh: MeshBuffers

    private val modelEntity = entityManager.create()
    private val wireEntity = entityManager.create()
    private val edgeEntity = entityManager.create()
    private val gridEntity = entityManager.create()
    private val planeEntity = entityManager.create()
    private val markerAEntity = entityManager.create()
    private val markerBEntity = entityManager.create()
    private val lineEntity = entityManager.create()
    private val keyLightEntity = entityManager.create()
    private val fillLightEntity = entityManager.create()
    private val rimLightEntity = entityManager.create()

    private var modelBuffers: MeshBuffers? = null
    private var wireIndices: IndexBuffer? = null
    private var edgeIndices: IndexBuffer? = null
    private var hasModelRenderable = false
    private var hasWireRenderable = false
    private var hasEdgeRenderable = false

    private val entitiesInScene = HashSet<Int>()

    private var swapChain: SwapChain? = null
    private var attachedSurfaceView: SurfaceView? = null
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var frameScheduled = false
    private var destroyed = false

    private var currentModel: LoadedModel? = null
    private var lastAppearance: AppearanceState? = null
    private var lastSection: SectionState? = null

    /**
     * The Shaded modes' ambient fill: a uniform light from every direction, added to the
     * same lit material and light rig Solid uses. Solid's lights leave faces turned away
     * from them dark; this lifts every face by the same amount, so nothing falls into
     * shadow while the key light, highlights, metallic and roughness all still shape the
     * part exactly as they do in Solid.
     *
     * Several attempts first drew Shaded with a separate unlit material that computed its
     * own lighting. However it was tuned it read as flat on the phone, it could not honour
     * the metallic and roughness sliders, and it needed the view's tone curve swapped to
     * reach the screen intact. Reusing the real material removes all of that.
     *
     * Only the constant spherical-harmonics band, so the fill has no direction of its own.
     */
    private val ambientFill: IndirectLight = IndirectLight.Builder()
        .irradiance(1, floatArrayOf(1f, 1f, 1f))
        .intensity(SHADED_AMBIENT_INTENSITY)
        .build(engine)
    private var ambientFillActive = false

    init {
        val compileStart = System.nanoTime()
        val compiled = runCatching { ViewerMaterials.create(engine) }
        // Runs on the main thread during the first composition, so its cost is worth
        // seeing: the full shader optimiser is slower than the pass-through it replaced.
        Log.i(TAG, "Materials compiled in ${(System.nanoTime() - compileStart) / 1_000_000} ms")
        materials = compiled.getOrNull()
        initializationError = compiled.exceptionOrNull()?.let {
            "The viewer's shaders could not be compiled on this device (${it.message})."
        }

        sphereMesh = GeometryFactory.buildSphere(engine)
        tubeMesh = GeometryFactory.buildTube(engine)
        quadMesh = GeometryFactory.buildQuad(engine)
        gridMesh = GeometryFactory.buildGrid(engine)

        skybox = Skybox.Builder()
            .color(BACKGROUND[0], BACKGROUND[1], BACKGROUND[2], 1f)
            .build(engine)
        scene.setSkybox(skybox)

        view.setScene(scene)
        view.setCamera(camera)
        view.setPostProcessingEnabled(true)
        view.setAntiAliasing(View.AntiAliasing.FXAA)
        view.setBlendMode(View.BlendMode.OPAQUE)

        camera.setExposure(APERTURE, SHUTTER_SPEED, SENSITIVITY)

        createLights()
        materials?.let { createStaticRenderables(it) }

        uiHelper.setRenderCallback(SurfaceCallback())
    }

    // ------------------------------------------------------------------ lifecycle

    fun attachTo(surfaceView: SurfaceView) {
        attachedSurfaceView = surfaceView
        uiHelper.attachTo(surfaceView)
    }

    fun resume() {
        if (destroyed || frameScheduled) return
        choreographer.postFrameCallback(frameCallback)
        frameScheduled = true
    }

    fun pause() {
        if (!frameScheduled) return
        choreographer.removeFrameCallback(frameCallback)
        frameScheduled = false
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        pause()
        uiHelper.detach()
        displayHelper.detach()

        // Renderables and their buffers first, then materials, then the engine itself.
        releaseModel()
        listOf(gridEntity, planeEntity, markerAEntity, markerBEntity, lineEntity).forEach {
            scene.remove(it)
            engine.destroyEntity(it)
        }
        listOf(keyLightEntity, fillLightEntity, rimLightEntity).forEach {
            scene.remove(it)
            engine.destroyEntity(it)
        }
        entitiesInScene.clear()

        sphereMesh.destroy(engine)
        tubeMesh.destroy(engine)
        quadMesh.destroy(engine)
        gridMesh.destroy(engine)

        listOf(
            modelMaterial, wireMaterial, edgeMaterial, gridMaterial, planeMaterial,
            markerAMaterial, markerBMaterial, lineMaterial,
        ).forEach { instance -> instance?.let { engine.destroyMaterialInstance(it) } }

        materials?.let {
            engine.destroyMaterial(it.model)
            engine.destroyMaterial(it.overlay)
            engine.destroyMaterial(it.translucent)
        }

        scene.setSkybox(null)
        engine.destroySkybox(skybox)
        // Detached first: destroying a light the scene still points at frees it under it.
        scene.indirectLight = null
        engine.destroyIndirectLight(ambientFill)

        engine.destroyCameraComponent(cameraEntity)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyRenderer(renderer)

        listOf(
            modelEntity, wireEntity, edgeEntity, gridEntity, planeEntity, markerAEntity,
            markerBEntity, lineEntity, keyLightEntity, fillLightEntity, rimLightEntity,
            cameraEntity,
        ).forEach { entityManager.destroy(it) }

        engine.destroy()
    }

    // ------------------------------------------------------------------ picking

    /**
     * Casts a ray through the tapped pixel and returns the intersection with the mesh in
     * world space, or `null` if the tap missed. Geometry hidden by the section plane is
     * ignored so the result always matches what is on screen.
     */
    fun pick(x: Float, y: Float): Vec3? {
        val state = stateProvider?.invoke() ?: return null
        val model = state.model ?: return null
        if (viewportWidth <= 0 || viewportHeight <= 0) return null

        val ray = state.camera.rayThrough(x, y, viewportWidth, viewportHeight)
        val clip = if (state.section.enabled) state.section.planeEquation() else null
        return model.bvh.intersect(ray.origin, ray.direction, clip)?.point
    }

    // ------------------------------------------------------------------ frame loop

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (destroyed) return
            choreographer.postFrameCallback(this)

            val state = stateProvider?.invoke()
            if (state != null && materials != null) {
                syncModel(state)
                syncAppearance(state)
                syncSection(state)
                syncMeasurement(state)
                syncCamera(state)
            }

            val chain = swapChain
            if (chain != null && uiHelper.isReadyToRender() && !destroyed) {
                if (renderer.beginFrame(chain, frameTimeNanos)) {
                    renderer.render(view)
                    renderer.endFrame()
                }
            }
        }
    }

    private fun syncModel(state: ViewerUiState) {
        val model = state.model
        if (model === currentModel) return

        releaseModel()
        currentModel = model
        placeGrid(model)
        if (model == null) return

        val buffers = GeometryFactory.buildModel(engine, model)
        modelBuffers = buffers

        val half = model.sourceSize * (model.worldPerSourceUnit * 0.5f)
        val box = Box(0f, 0f, 0f, half.x + 1e-3f, half.y + 1e-3f, half.z + 1e-3f)

        RenderableManager.Builder(1)
            .boundingBox(box)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, buffers.vertices, buffers.indices, 0, buffers.indexCount)
            .material(0, requireNotNull(modelMaterial))
            .castShadows(false)
            .receiveShadows(false)
            .build(engine, modelEntity)
        // Identity transform: the fit transform is baked into the vertex data, which is
        // what lets the clipping shader work in object space.
        transformManager.create(modelEntity)
        hasModelRenderable = true

        val wire = GeometryFactory.buildWireframeIndices(engine, model)
        wireIndices = wire
        if (wire != null) {
            // Three line segments per triangle, see buildWireframeIndices.
            val wireIndexCount = model.triangleCount * 6
            RenderableManager.Builder(1)
                .boundingBox(box)
                .geometry(0, RenderableManager.PrimitiveType.LINES, buffers.vertices, wire, 0, wireIndexCount)
                .material(0, requireNotNull(wireMaterial))
                .castShadows(false)
                .receiveShadows(false)
                .build(engine, wireEntity)
            transformManager.create(wireEntity)
            hasWireRenderable = true
        } else if (model.triangleCount > GeometryFactory.MAX_WIREFRAME_TRIANGLES) {
            onNotice?.invoke(
                "Wireframe is unavailable above " +
                    "${GeometryFactory.MAX_WIREFRAME_TRIANGLES / 1_000_000f} M triangles.",
            )
        }

        val edges = model.featureEdges
        if (edges != null && edges.isNotEmpty()) {
            val buffer = GeometryFactory.buildLineIndices(engine, edges)
            edgeIndices = buffer
            RenderableManager.Builder(1)
                .boundingBox(box)
                .geometry(0, RenderableManager.PrimitiveType.LINES, buffers.vertices, buffer, 0, edges.size)
                .material(0, requireNotNull(edgeMaterial))
                .castShadows(false)
                .receiveShadows(false)
                .build(engine, edgeEntity)
            transformManager.create(edgeEntity)
            hasEdgeRenderable = true
        } else if (edges == null) {
            onNotice?.invoke(
                "Edge outlines are unavailable above " +
                    "${FeatureEdges.MAX_TRIANGLES / 1_000} k triangles.",
            )
        }

        // Force the next sync to push every parameter to the new renderables.
        lastAppearance = null
        lastSection = null
    }

    /**
     * Sits the ground grid (the XY plane) under the model's base, so the part rests on it
     * the way it would on a build plate. A flat part would otherwise float well above a
     * grid fixed at -1, the bottom of a model that fills its whole normalised box.
     */
    private fun placeGrid(model: LoadedModel?) {
        val z = if (model != null) {
            -(model.sourceSize.z * model.worldPerSourceUnit * 0.5f) - GRID_CLEARANCE
        } else {
            EMPTY_GRID_Z
        }
        transformManager.setTransform(
            transformManager.getInstance(gridEntity),
            Mat4.translation(Vec3(0f, 0f, z)),
        )
    }

    private fun releaseModel() {
        if (hasModelRenderable) {
            setInScene(modelEntity, false)
            engine.destroyEntity(modelEntity)
            hasModelRenderable = false
        }
        if (hasWireRenderable) {
            setInScene(wireEntity, false)
            engine.destroyEntity(wireEntity)
            hasWireRenderable = false
        }
        if (hasEdgeRenderable) {
            setInScene(edgeEntity, false)
            engine.destroyEntity(edgeEntity)
            hasEdgeRenderable = false
        }
        wireIndices?.let { engine.destroyIndexBuffer(it) }
        wireIndices = null
        edgeIndices?.let { engine.destroyIndexBuffer(it) }
        edgeIndices = null
        modelBuffers?.destroy(engine)
        modelBuffers = null
        currentModel = null
    }

    private fun syncAppearance(state: ViewerUiState) {
        val appearance = state.appearance
        if (appearance != lastAppearance) {
            lastAppearance = appearance
            val red = toLinear(appearance.color.red)
            val green = toLinear(appearance.color.green)
            val blue = toLinear(appearance.color.blue)
            modelMaterial?.apply {
                setParameter(ViewerMaterials.PARAM_BASE_COLOR, red, green, blue)
                setParameter(ViewerMaterials.PARAM_METALLIC, appearance.metallic)
                setParameter(ViewerMaterials.PARAM_ROUGHNESS, appearance.roughness)
            }
        }

        setInScene(modelEntity, hasModelRenderable && appearance.shading.drawsSolid)
        setInScene(wireEntity, hasWireRenderable && appearance.shading.drawsWireframe)
        setInScene(edgeEntity, hasEdgeRenderable && appearance.shading.drawsFeatureEdges)
        setInScene(gridEntity, appearance.showGrid)

        val fill = appearance.shading.evenlyLit
        if (fill != ambientFillActive) {
            ambientFillActive = fill
            scene.indirectLight = if (fill) ambientFill else null
        }
    }

    private fun syncSection(state: ViewerUiState) {
        val section = state.section
        if (section != lastSection) {
            lastSection = section

            val plane = section.planeEquation()
            val enabled = if (section.enabled) 1f else 0f
            for (instance in listOfNotNull(modelMaterial, wireMaterial, edgeMaterial)) {
                instance.setParameter(ViewerMaterials.PARAM_CLIP_PLANE, plane[0], plane[1], plane[2], plane[3])
                instance.setParameter(ViewerMaterials.PARAM_CLIP_ENABLED, enabled)
            }

            // Orient the plane visualisation: its local +Z is the plane normal.
            val normal = section.normal
            val right = normal.anyPerpendicular()
            val up = normal cross right

            // Size each in-plane axis to the model's own extent along that axis, not to
            // its bounding sphere: a plane cutting a flat or elongated part across its
            // short side only needs to span the short side, and a sphere-sized quad would
            // tower over the model.
            val model = currentModel
            val halfWidth: Float
            val halfHeight: Float
            if (model != null) {
                val half = model.sourceSize * (model.worldPerSourceUnit * 0.5f)
                halfWidth = planeExtent(half, right)
                halfHeight = planeExtent(half, up)
            } else {
                halfWidth = PLANE_FALLBACK_SIZE
                halfHeight = PLANE_FALLBACK_SIZE
            }

            transformManager.setTransform(
                transformManager.getInstance(planeEntity),
                Mat4.fromBasis(right * halfWidth, up * halfHeight, normal, section.origin),
            )
        }

        setInScene(planeEntity, section.enabled && section.showPlane && state.hasModel)
    }

    private fun syncMeasurement(state: ViewerUiState) {
        val measurement = state.measurement
        // Markers are sized relative to the orbit distance so they keep roughly the same
        // size on screen as the user zooms.
        val markerRadius = (MARKER_SCREEN_SCALE * state.camera.distance).coerceIn(0.004f, 0.12f)

        val a = measurement.pointA
        val b = measurement.pointB

        if (a != null) {
            transformManager.setTransform(
                transformManager.getInstance(markerAEntity),
                Mat4.scaleTranslation(markerRadius, a),
            )
        }
        if (b != null) {
            transformManager.setTransform(
                transformManager.getInstance(markerBEntity),
                Mat4.scaleTranslation(markerRadius, b),
            )
        }
        if (a != null && b != null) {
            transformManager.setTransform(
                transformManager.getInstance(lineEntity),
                tubeTransform(a, b, markerRadius * LINE_THICKNESS_RATIO),
            )
        }

        setInScene(markerAEntity, a != null)
        setInScene(markerBEntity, b != null)
        setInScene(lineEntity, a != null && b != null)
    }

    /**
     * How far the model reaches along [axis] from its centre — the support function of
     * its bounding box — with a margin so the plane overhangs the cut slightly.
     */
    private fun planeExtent(half: Vec3, axis: Vec3): Float {
        val support = abs(half.x * axis.x) + abs(half.y * axis.y) + abs(half.z * axis.z)
        return (support * PLANE_SIZE_FACTOR).coerceAtLeast(MIN_PLANE_EXTENT)
    }

    /** Stretches the unit tube (origin to +Z) between two points. */
    private fun tubeTransform(from: Vec3, to: Vec3, radius: Float): FloatArray {
        val axis = to - from
        val length = axis.length
        if (length < 1e-9f) return Mat4.scaleTranslation(radius, from)
        val forward = axis * (1f / length)
        val right = forward.anyPerpendicular()
        val up = forward cross right
        return Mat4.fromBasis(right * radius, up * radius, forward * length, from)
    }

    private fun syncCamera(state: ViewerUiState) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        val cameraState = state.camera
        val aspect = viewportWidth.toDouble() / viewportHeight.toDouble()

        when (cameraState.projection) {
            ProjectionMode.PERSPECTIVE -> camera.setProjection(
                cameraState.fovDegrees.toDouble(),
                aspect,
                cameraState.near.toDouble(),
                cameraState.far.toDouble(),
                Camera.Fov.VERTICAL,
            )

            ProjectionMode.ORTHOGRAPHIC -> {
                val halfHeight = cameraState.orthoHeight * 0.5
                val halfWidth = halfHeight * aspect
                camera.setProjection(
                    Camera.Projection.ORTHO,
                    -halfWidth, halfWidth,
                    -halfHeight, halfHeight,
                    cameraState.near.toDouble(),
                    cameraState.far.toDouble(),
                )
            }
        }

        val eye = cameraState.eye
        val target = cameraState.target
        camera.lookAt(
            eye.x.toDouble(), eye.y.toDouble(), eye.z.toDouble(),
            target.x.toDouble(), target.y.toDouble(), target.z.toDouble(),
            CameraState.WORLD_UP.x.toDouble(), CameraState.WORLD_UP.y.toDouble(), CameraState.WORLD_UP.z.toDouble(),
        )

        // The key light follows the camera so whatever the user looks at is always lit,
        // tilted slightly downwards (-Z) so upper faces read brighter than lower ones.
        val forward = cameraState.forward
        lightManager.setDirection(
            lightManager.getInstance(keyLightEntity),
            forward.x, forward.y, forward.z - 0.35f,
        )
    }

    // ------------------------------------------------------------------ scene setup

    private fun createLights() {
        // Directions are for the Z-up world. They are the original Y-up rig turned with
        // the world, (x, y, z) -> (x, -z, y), so the lighting relative to the default
        // camera is unchanged. The key light's is replaced every frame.
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1f, 0.98f, 0.95f)
            .intensity(KEY_LIGHT_LUX)
            .direction(0f, 1f, -0.4f)
            .castShadows(false)
            .build(engine, keyLightEntity)

        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(0.80f, 0.86f, 1f)
            .intensity(FILL_LIGHT_LUX)
            .direction(0.8f, -0.3f, 0.5f)
            .castShadows(false)
            .build(engine, fillLightEntity)

        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1f, 0.95f, 0.85f)
            .intensity(RIM_LIGHT_LUX)
            .direction(-0.6f, -0.75f, 0.2f)
            .castShadows(false)
            .build(engine, rimLightEntity)

        listOf(keyLightEntity, fillLightEntity, rimLightEntity).forEach {
            scene.addEntity(it)
            entitiesInScene.add(it)
        }
    }

    private fun createStaticRenderables(materials: ViewerMaterials) {
        modelMaterial = materials.model.createInstance().apply {
            setParameter(ViewerMaterials.PARAM_BASE_COLOR, 0.76f, 0.79f, 0.84f)
            setParameter(ViewerMaterials.PARAM_METALLIC, 0.25f)
            setParameter(ViewerMaterials.PARAM_ROUGHNESS, 0.45f)
            setParameter(ViewerMaterials.PARAM_CLIP_PLANE, 1f, 0f, 0f, 0f)
            setParameter(ViewerMaterials.PARAM_CLIP_ENABLED, 0f)
        }

        // Only lines that lie on the model's surface are pulled towards the camera; the
        // grid, markers and measurement line stay exactly where they are.
        wireMaterial = overlayInstance(materials, WIREFRAME_COLOR, clippable = true, pull = SURFACE_LINE_PULL)
        edgeMaterial = overlayInstance(materials, EDGE_COLOR, clippable = true, pull = SURFACE_LINE_PULL)
        gridMaterial = overlayInstance(materials, GRID_COLOR, clippable = false)
        markerAMaterial = overlayInstance(materials, MARKER_A_COLOR, clippable = false)
        markerBMaterial = overlayInstance(materials, MARKER_B_COLOR, clippable = false)
        lineMaterial = overlayInstance(materials, LINE_COLOR, clippable = false)

        planeMaterial = materials.translucent.createInstance().apply {
            setParameter(
                ViewerMaterials.PARAM_BASE_COLOR,
                PLANE_COLOR[0], PLANE_COLOR[1], PLANE_COLOR[2], PLANE_COLOR[3],
            )
        }

        buildStatic(gridEntity, gridMesh, RenderableManager.PrimitiveType.LINES, gridMaterial!!, 3f)
        buildStatic(planeEntity, quadMesh, RenderableManager.PrimitiveType.TRIANGLES, planeMaterial!!, PLANE_FALLBACK_SIZE * 2f)
        buildStatic(markerAEntity, sphereMesh, RenderableManager.PrimitiveType.TRIANGLES, markerAMaterial!!, 2f)
        buildStatic(markerBEntity, sphereMesh, RenderableManager.PrimitiveType.TRIANGLES, markerBMaterial!!, 2f)
        buildStatic(lineEntity, tubeMesh, RenderableManager.PrimitiveType.TRIANGLES, lineMaterial!!, 4f)

        // Everything but the grid starts hidden; the per-frame sync adds what is needed.
        placeGrid(null)
        setInScene(gridEntity, true)
    }

    private fun overlayInstance(
        materials: ViewerMaterials,
        color: FloatArray,
        clippable: Boolean,
        pull: Float = 0f,
    ): MaterialInstance = materials.overlay.createInstance().apply {
        setParameter(ViewerMaterials.PARAM_BASE_COLOR, color[0], color[1], color[2])
        // A plane that can never cut anything keeps the shader branch-free for overlays.
        setParameter(ViewerMaterials.PARAM_CLIP_PLANE, 0f, 1f, 0f, -1e6f)
        setParameter(ViewerMaterials.PARAM_CLIP_ENABLED, if (clippable) 1f else 0f)
        setParameter(ViewerMaterials.PARAM_PULL_TO_CAMERA, pull)
    }

    private fun buildStatic(
        entity: Int,
        mesh: MeshBuffers,
        type: RenderableManager.PrimitiveType,
        material: MaterialInstance,
        boundingHalfExtent: Float,
    ) {
        RenderableManager.Builder(1)
            // These are transformed per frame, so frustum culling is turned off rather
            // than kept in sync with a moving bounding box.
            .boundingBox(Box(0f, 0f, 0f, boundingHalfExtent, boundingHalfExtent, boundingHalfExtent))
            .culling(false)
            .geometry(0, type, mesh.vertices, mesh.indices, 0, mesh.indexCount)
            .material(0, material)
            .castShadows(false)
            .receiveShadows(false)
            .build(engine, entity)

        transformManager.create(entity)
    }

    private fun setInScene(entity: Int, visible: Boolean) {
        val present = entity in entitiesInScene
        if (visible == present) return
        if (visible) {
            scene.addEntity(entity)
            entitiesInScene.add(entity)
        } else {
            scene.remove(entity)
            entitiesInScene.remove(entity)
        }
    }

    // ------------------------------------------------------------------ surface

    private inner class SurfaceCallback : UiHelper.RendererCallback {
        override fun onNativeWindowChanged(surface: Surface) {
            swapChain?.let { engine.destroySwapChain(it) }
            swapChain = engine.createSwapChain(surface)
            attachedSurfaceView?.display?.let { displayHelper.attach(renderer, it) }
        }

        override fun onDetachedFromSurface() {
            displayHelper.detach()
            swapChain?.let {
                engine.destroySwapChain(it)
                // Make sure the GPU is done with the surface before it goes away.
                engine.flushAndWait()
                swapChain = null
            }
        }

        override fun onResized(width: Int, height: Int) {
            viewportWidth = width
            viewportHeight = height
            view.setViewport(Viewport(0, 0, width, height))
            onViewportResized?.invoke(width, height)
        }
    }

    private companion object {
        const val TAG = "Inspect3D"

        /** sRGB -> linear, which is the space Filament's material parameters live in. */
        fun toLinear(channel: Float): Float =
            if (channel <= 0.04045f) channel / 12.92f else ((channel + 0.055f) / 1.055f).pow(2.4f)

        val BACKGROUND = floatArrayOf(0.012f, 0.017f, 0.025f)
        val GRID_COLOR = floatArrayOf(0.055f, 0.075f, 0.10f)
        val WIREFRAME_COLOR = floatArrayOf(0.18f, 0.45f, 0.70f)

        /** Near-black outlines, as a CAD viewer draws edges over a shaded part. */
        val EDGE_COLOR = floatArrayOf(0.012f, 0.014f, 0.018f)

        /**
         * How far edge and wireframe lines are drawn nearer the camera, as a fraction of
         * the camera distance: enough to win the depth test against the surface they lie
         * on, small enough not to show through anything genuinely in front of them.
         */
        const val SURFACE_LINE_PULL = 0.001f

        /**
         * The Shaded modes' ambient fill, in the same units as the light rig below and
         * seen through the same exposure. The key light gives a face it hits squarely
         * colour x 95,000 / pi, about colour x 30,000; the fill gives every face colour x
         * this value, since Filament's irradiance already includes the 1/pi. A third of
         * the key: enough that no face reads as in shadow, not so much that the key light
         * stops shaping the part.
         */
        const val SHADED_AMBIENT_INTENSITY = 10_000f

        val MARKER_A_COLOR = floatArrayOf(0.90f, 0.22f, 0.15f)
        val MARKER_B_COLOR = floatArrayOf(0.15f, 0.45f, 0.90f)
        val LINE_COLOR = floatArrayOf(1.0f, 0.62f, 0.08f)
        val PLANE_COLOR = floatArrayOf(0.35f, 0.65f, 1.0f, 0.13f)

        /** Grid height with nothing loaded: where a model filling its box would rest. */
        const val EMPTY_GRID_Z = -1.001f

        /** Gap between the model's base and the grid, enough to avoid depth fighting. */
        const val GRID_CLEARANCE = 0.001f

        /** A little past the model's edge, so the plane overhangs the cut. */
        const val PLANE_SIZE_FACTOR = 1.18f

        /** Keeps the plane visible even when slicing a very thin part. */
        const val MIN_PLANE_EXTENT = 0.15f

        /** Used until a model is loaded: √3, the worst case for a normalised model. */
        const val PLANE_FALLBACK_SIZE = 1.74f

        const val MARKER_SCREEN_SCALE = 0.009f
        const val LINE_THICKNESS_RATIO = 0.28f

        const val APERTURE = 16f
        const val SHUTTER_SPEED = 1f / 125f
        const val SENSITIVITY = 100f

        const val KEY_LIGHT_LUX = 95_000f
        const val FILL_LIGHT_LUX = 38_000f
        const val RIM_LIGHT_LUX = 24_000f
    }
}
