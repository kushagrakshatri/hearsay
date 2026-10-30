package dev.offlineflow.llm

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.util.Log

/**
 * Speculative decoding switches, set by the app from its settings (process-wide).
 *
 * - llama.cpp (GGUF): prompt-lookup drafts copied from the transcript, verified in one batch.
 *   Greedy-exact; read on every call, so the switch applies to the next dictation.
 * - LiteRT-LM (Gemma 4 .litertlm): the model's multi-token-prediction (MTP) drafter. Set when
 *   the engine loads: on for GPUs except PowerVR (MTP regresses there, LiteRT-LM issue #2227),
 *   on for the CPU when [mtpOnCpu] (Google recommends it for rewrite tasks on E2B).
 */
object Speculation {
    /** llama.cpp prompt-lookup decoding. */
    @Volatile var draftFromTranscript = true

    /** LiteRT-LM MTP when Gemma runs on the CPU (applies at the next model load). */
    @Volatile var mtpOnCpu = true

    /**
     * Draft tokens per llama.cpp verify step (also the hybrid-model rollback depth). 3 drafts with
     * a 3-token match was fastest in the desktop eval (tools/eval/RESULTS.md); longer drafts are
     * rejected more often and each verify pass costs more on a CPU.
     */
    const val LLAMA_DRAFT = 3

    private const val TAG = "hearsay-litert"

    /** GL renderer string, e.g. "Adreno (TM) 830" or "PowerVR D-Series DXT-48-1536"; null if unknown. */
    val glRenderer: String? by lazy { queryRenderer() }

    /**
     * True for PowerVR GPUs: the GL renderer says so, or the chip is a Google Tensor G5 or newer
     * (Pixel 10 onward, PowerVR DXT) in case the renderer can't be read.
     */
    val powerVrGpu: Boolean by lazy {
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else ""
        val tensorGen = Regex("Tensor\\s*G(\\d+)", RegexOption.IGNORE_CASE).find(soc)?.groupValues?.get(1)?.toIntOrNull()
        val r = glRenderer
        val pvr = (r?.contains("PowerVR", ignoreCase = true) == true) || (tensorGen != null && tensorGen >= 5)
        Log.i(TAG, "gpu: renderer=${r ?: "?"} soc=${soc.ifBlank { "?" }} powerVR=$pvr")
        detectedRenderer = r
        pvr
    }

    /** The GL renderer once [powerVrGpu] has looked it up (when Gemma loads on the GPU); null before. */
    @Volatile var detectedRenderer: String? = null
        private set

    private fun queryRenderer(): String? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return null
        val v = IntArray(2)
        if (!EGL14.eglInitialize(display, v, 0, v, 1)) return null
        try {
            val configs = arrayOfNulls<EGLConfig>(1)
            val n = IntArray(1)
            val attrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0) || n[0] == 0) return null
            val ctx = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            if (ctx == EGL14.EGL_NO_CONTEXT) return null
            val surf = EGL14.eglCreatePbufferSurface(display, configs[0],
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            try {
                if (surf == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, surf, surf, ctx)) return null
                return GLES20.glGetString(GLES20.GL_RENDERER)
            } finally {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surf)
                EGL14.eglDestroyContext(display, ctx)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "couldn't read the GL renderer", t)
            return null
        } finally {
            EGL14.eglTerminate(display)
        }
    }
}
