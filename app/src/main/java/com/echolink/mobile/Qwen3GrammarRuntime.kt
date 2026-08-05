package com.echolink.mobile

import android.content.Context
import java.io.File
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Runs the selected Qwen3 model through llama.cpp. It never performs network inference.
 */
object Qwen3RuntimeTierPolicy {
    fun canRun(selected: Qwen3Tier, installed: Qwen3Tier): Boolean =
        installed.ordinal <= selected.ordinal
}

class Qwen3GrammarRuntime(context: Context) {
    val type: LocalBackendType = LocalBackendType.LLAMA_CPP_CPU
    private val appContext = context.applicationContext
    private val modelManager = Qwen3ModelManager.get(appContext)
    private val engine by lazy { AiChat.getInferenceEngine(appContext) }
    private val idleExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "manuscript-qwen-idle").apply { isDaemon = true }
    }
    private var idleRelease: ScheduledFuture<*>? = null
    private var loadedPath: String? = null
    private var loadedContextSize = 0
    private var loadedThreadCount = 0
    private var systemPromptCached = false
    private var loadedVulkan = false
    private var cachedPromptTone: WritingTone? = null
    private var cachedPromptCompact = false
    @Volatile private var boundModel: BoundQwenModel? = null

    init {
        // Before anything can touch the native engine, make sure an unproven
        // GPU cannot be selected in this process.
        VulkanProbe.suppressUntilProven(appContext, modelManager.installedModel())
        modelManager.ensureModelAsync()
    }

    fun isReady(): Boolean {
        val bound = boundModel ?: return false
        return bound.file.isFile && bound.file.length() == bound.tier.sizeBytes
    }

    /**
     * Binds correction to the exact artifact selected by AiModelManager.
     * Qwen3ModelManager has its own adaptive policy for legacy-only callers;
     * consulting it again here could silently replace a balanced model with
     * the compact fallback between document sections.
     */
    fun bindModel(model: File, tier: Qwen3Tier) {
        require(model.name == tier.fileName)
        require(model.isFile && model.length() == tier.sizeBytes)
        boundModel = BoundQwenModel(model, tier)
    }

    /**
     * True when this device proved, in the isolated probe process, that its
     * Vulkan driver survives our pipelines. Devices that never probed, or
     * whose probe process died, stay on CPU.
     */
    private fun vulkanUsable(model: File): Boolean =
        BuildConfig.MANUSCRIPT_VULKAN &&
            VulkanProbe.ensureProbed(appContext, model) == VulkanProbe.Decision.USABLE

    /**
     * llama.cpp auto-selects any Vulkan device it can see, so a driver that
     * aborts during pipeline creation would take this process down before the
     * probe result could matter. GGML_VK_VISIBLE_DEVICES with an empty list
     * leaves the Vulkan backend loaded but device-less, which is the only
     * reliable way to keep a bad driver out of the hosting process.
     */
    private fun applyVulkanVisibility(useVulkan: Boolean) {
        if (!BuildConfig.MANUSCRIPT_VULKAN) return
        runCatching {
            if (useVulkan) {
                android.system.Os.unsetenv("GGML_VK_VISIBLE_DEVICES")
            } else {
                android.system.Os.setenv("GGML_VK_VISIBLE_DEVICES", "", true)
            }
        }
    }

    fun rewrite(source: String, tone: WritingTone, strict: Boolean = false): String? {
        val bound = boundModel ?: run {
            releaseResources()
            modelManager.ensureModelAsync()
            return null
        }
        val model = bound.file
        val modelTier = bound.tier
        val profile = modelManager.runtimeProfile(modelTier)
        idleRelease?.cancel(false)
        return try {
            synchronized(LocalInferenceCoordinator.lock) {
                runBlocking {
                    awaitNativeInitialization()
                    // Ask the isolated probe before the first GPU-backed
                    // load of this model. A CPU-only answer leaves llama.cpp
                    // on the CPU backend exactly as before.
                    val useVulkan = vulkanUsable(model)
                    applyVulkanVisibility(useVulkan)
                    if (
                        loadedPath != model.absolutePath ||
                        loadedContextSize != profile.contextSize ||
                        loadedThreadCount != profile.threadCount ||
                        loadedVulkan != useVulkan ||
                        engine.state.value !is InferenceEngine.State.ModelReady
                    ) {
                        if (engine.state.value is InferenceEngine.State.ModelReady) engine.cleanUp()
                        engine.loadModel(
                            model.absolutePath,
                            profile.contextSize,
                            profile.threadCount,
                        )
                        loadedPath = model.absolutePath
                        loadedContextSize = profile.contextSize
                        loadedThreadCount = profile.threadCount
                        loadedVulkan = useVulkan
                        systemPromptCached = false
                    }
                    val rewritten = StringBuilder()
                    val compactProfile = profile.contextSize <= 512
                    for (chunk in LocalRewriteChunker.split(
                        source,
                        profile.maximumSourceCharacters,
                    )) {
                        prepareCachedSystemPrompt(tone, compactProfile)
                        val prompt = buildString {
                            appendLine("/no_think")
                            if (strict) appendLine(STRICT_RETRY_INSTRUCTION)
                            appendLine(TonePromptLibrary.payload(chunk.text))
                        }
                        val chunkStartedAt = System.nanoTime()
                        val chunkOutput = generate(
                            prompt,
                            chunk.text,
                            profile.maximumOutputTokens,
                        )
                        // Feed the self-tuning benchmark: a device whose
                        // BALANCED inferences run slow automatically drops
                        // Auto mode to the compact tier and downloads it.
                        modelManager.recordInference(
                            modelTier,
                            (System.nanoTime() - chunkStartedAt) / 1_000_000,
                            chunkOutput.isNotBlank(),
                        )
                        if (chunkOutput.isBlank()) return@runBlocking ""
                        rewritten.append(chunkOutput)
                        rewritten.append(chunk.separatorAfter)
                    }
                    rewritten.toString()
                }
            }
                .takeIf { it.isNotBlank() }
        } finally {
            scheduleIdleRelease()
        }
    }

    /**
     * Trial load plus one tiny generation, used only by the isolated Vulkan
     * probe process. Returning true means this device's driver survived
     * pipeline creation; a driver that aborts takes the probe process down
     * instead of returning.
     */
    fun probeGeneration(model: File): Boolean = runCatching {
        // The probe is the one place a GPU is deliberately allowed: this
        // process exists to find out whether the driver survives.
        runCatching { android.system.Os.unsetenv("GGML_VK_VISIBLE_DEVICES") }
        synchronized(LocalInferenceCoordinator.lock) {
            runBlocking {
                awaitNativeInitialization()
                if (engine.state.value is InferenceEngine.State.ModelReady) engine.cleanUp()
                engine.loadModel(model.absolutePath, 512, 2)
                engine.setSystemPrompt("You are a test.")
                val reply = StringBuilder()
                engine.sendUserPrompt("Reply with READY.", 8).collect { reply.append(it) }
                reply.isNotBlank()
            }
        }
    }.getOrDefault(false)

    fun releaseResources() {
        idleRelease?.cancel(false)
        if (loadedPath == null) return
        synchronized(LocalInferenceCoordinator.lock) {
            when (engine.state.value) {
                is InferenceEngine.State.ModelReady,
                is InferenceEngine.State.Error,
                -> engine.cleanUp()
                else -> Unit
            }
            loadedPath = null
            loadedContextSize = 0
            loadedThreadCount = 0
            systemPromptCached = false
        }
    }

    private suspend fun prepareCachedSystemPrompt(tone: WritingTone, compact: Boolean) {
        // The tone's full instruction block lives in the cached system prompt,
        // so each chunk prefILLS only its own text instead of re-sending
        // 400-600 instruction tokens. The cache re-arms only when the tone or
        // profile changes.
        if (systemPromptCached && cachedPromptTone == tone && cachedPromptCompact == compact) {
            engine.resetConversation()
            return
        }
        val instructions = if (compact) {
            TonePromptLibrary.compactInstructions(tone)
        } else {
            TonePromptLibrary.instructions(tone)
        }
        engine.setSystemPrompt(SYSTEM_PROMPT + "\n" + instructions)
        systemPromptCached = true
        cachedPromptTone = tone
        cachedPromptCompact = compact
    }

    @Volatile
    private var keepWarm = false

    /**
     * While a review session is open, the model stays loaded regardless of
     * the idle timer so the next paragraph never pays a cold reload. When the
     * session ends the normal idle release resumes.
     */
    fun setKeepWarm(warm: Boolean) {
        keepWarm = warm
        if (warm) {
            idleRelease?.cancel(false)
        } else {
            scheduleIdleRelease()
        }
    }

    /**
     * Loads the selected model without generating, so the first correction of
     * a session does not pay model-load latency.
     */
    fun preload() {
        val bound = boundModel ?: return
        val model = bound.file
        val modelTier = bound.tier
        val profile = modelManager.runtimeProfile(modelTier)
        idleRelease?.cancel(false)
        runCatching {
            synchronized(LocalInferenceCoordinator.lock) {
                runBlocking {
                    awaitNativeInitialization()
                    if (
                        loadedPath != model.absolutePath ||
                        engine.state.value !is InferenceEngine.State.ModelReady
                    ) {
                        if (engine.state.value is InferenceEngine.State.ModelReady) engine.cleanUp()
                        engine.loadModel(
                            model.absolutePath,
                            profile.contextSize,
                            profile.threadCount,
                        )
                        loadedPath = model.absolutePath
                        loadedContextSize = profile.contextSize
                        loadedThreadCount = profile.threadCount
                        systemPromptCached = false
                    }
                }
            }
        }
        scheduleIdleRelease()
    }

    private fun scheduleIdleRelease() {
        if (keepWarm) return
        idleRelease?.cancel(false)
        idleRelease = idleExecutor.schedule(
            { releaseResources() },
            IDLE_RELEASE_MINUTES,
            TimeUnit.MINUTES,
        )
    }

    private suspend fun generate(
        prompt: String,
        source: String,
        maximumOutputTokens: Int,
    ): String {
        val output = StringBuilder()
        engine.sendUserPrompt(
            prompt,
            outputTokenLimit(source, maximumOutputTokens),
        ).collect { output.append(it) }
        return cleanModelOutput(output.toString())
    }

    private suspend fun awaitNativeInitialization() {
        val current = engine.state.value
        if (
            current is InferenceEngine.State.Initialized ||
            current is InferenceEngine.State.ModelReady
        ) {
            return
        }
        val settled = engine.state.first {
            it is InferenceEngine.State.Initialized ||
                it is InferenceEngine.State.ModelReady ||
                it is InferenceEngine.State.Error
        }
        if (settled is InferenceEngine.State.Error) throw settled.exception
    }

    private fun outputTokenLimit(source: String, maximum: Int): Int =
        (source.length / 3 + 64).coerceIn(minOf(80, maximum), maximum)

    private fun cleanModelOutput(value: String): String {
        var result = value.trim()
        result = Regex("(?s)<think>.*?</think>").replace(result, "").trim()
        if ("</think>" in result) result = result.substringAfterLast("</think>").trim()
        if (result.startsWith("```") && result.endsWith("```")) {
            result = result.removePrefix("```").removeSuffix("```").trim()
            result = result.removePrefix("text").trimStart()
        }
        if (
            result.length >= 2 &&
            result.first() == '"' &&
            result.last() == '"'
        ) {
            result = result.substring(1, result.lastIndex)
        }
        return result.trim()
    }

    companion object {
        private const val STRICT_RETRY_INSTRUCTION =
            "The prior answer failed validation. Correct every error, preserve every fact and " +
                "formatting boundary, follow the requested tone exactly, and return only the " +
                "complete replacement text."
        private const val IDLE_RELEASE_MINUTES = 2L
        private val SYSTEM_PROMPT = """
            You are Manuscript, an elite English copy editor.
            Produce the complete replacement text and nothing else.
            Correct every grammar, spelling, punctuation, capitalization, formatting, and sentence-structure error.
            Follow the requested tone exactly while preserving meaning, facts, names, numbers, dates, links, quotations, and paragraph breaks.
            Never use em dashes.
            Never explain, label, preface, quote, or analyze the answer.
            Never output thinking tags or instructions.
        """.trimIndent()
    }

    private data class BoundQwenModel(val file: File, val tier: Qwen3Tier)
}
