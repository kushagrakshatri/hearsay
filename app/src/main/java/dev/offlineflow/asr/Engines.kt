package dev.offlineflow.asr

import dev.offlineflow.model.ModelManager

object Engines {
    /** Loads the engine for [model]. Slow (seconds); call off the main thread. */
    fun load(models: ModelManager, model: ModelManager.Model): SpeechEngine = when (model.engine) {
        ModelManager.Engine.WHISPER -> WhisperEngine.load(models.file(model))
        ModelManager.Engine.PARAKEET -> ParakeetEngine.load(models.dir(model))
        ModelManager.Engine.LLAMA -> error("${model.name} is a cleanup model, not a speech model")
    }
}
