package com.dpsmpx.dpai.providers

/** Только модели из ответа провайдера; первичная настройка не выбирает embedding вместо агента (D-214). */
object DevelopmentModels {
    fun eligible(models: List<ModelInfo>): List<ModelInfo> = models.filter { model ->
        val id = model.id.lowercase()
        model.supportsTools != false && listOf("embed", "rerank", "whisper", "dall-e", "image", "realtime", "audio", "tts-", "veo-", "moderation", "transcribe", "babbage", "davinci").none { it in id }
    }.distinctBy { it.id }.sortedBy { it.id }

    fun preferred(models: List<ModelInfo>, saved: String?): String? {
        val options = eligible(models)
        saved?.takeIf { id -> options.any { it.id == id } }?.let { return it }
        options.firstOrNull { it.id == "claude-opus-5-5" }?.let { return it.id }
        fun score(model: ModelInfo): Int = when {
            model.supportsTools == true -> 100
            listOf("claude", "gpt-", "codex", "gemini", "deepseek-chat", "mistral-large").any { it in model.id.lowercase() } -> 50
            else -> 0
        }
        return options.sortedWith(compareByDescending<ModelInfo>(::score).thenByDescending { it.id }).firstOrNull()?.id
    }
}
