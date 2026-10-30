package com.nexus.agent.domain.memory

/** One remembered interaction. Ported from Mashaaer's `context_tracker.py` SessionLog. */
data class MemoryEntry(
    val userInput: String,
    val reply: String = "",
    val emotion: String? = null,
    val intent: String? = null,
    val topic: String? = null,
    val timestampMillis: Long = 0L
)

/**
 * Formats recent conversation memory into an emotion-aware instruction block for the model.
 * Ported from Mashaaer's `context_tracker.py` `format_context_for_ai` + emotion instructions.
 */
object ConversationMemory {

    private val emotionInstructions: Map<String, String> = mapOf(
        "happy" to "المستخدم يبدو سعيدًا، تفاعل بإيجابية ودفء.",
        "sad" to "المستخدم يبدو حزينًا، كن متعاطفًا ولطيفًا في ردك.",
        "angry" to "المستخدم يبدو غاضبًا، كن هادئًا ومتفهمًا في ردك.",
        "fear" to "المستخدم يبدو قلقًا، كن مطمئنًا وداعمًا في ردك.",
        "surprise" to "المستخدم يبدو مندهشًا، وضح له الأمور بشكل واضح.",
        "disgust" to "المستخدم يبدو منزعجًا، كن متفهمًا ولا تكن دفاعيًا."
    )

    /** Most frequent emotion across [entries], or "neutral" when none are recorded. */
    fun dominantEmotion(entries: List<MemoryEntry>): String {
        val emotions = entries.mapNotNull { it.emotion }
        if (emotions.isEmpty()) return "neutral"
        return emotions.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "neutral"
    }

    /**
     * Builds a compact instruction block: the recent user turns plus an emotion-aware
     * response directive. Returns "" when there is nothing to remember.
     */
    fun formatContextForAi(entries: List<MemoryEntry>, maxEntries: Int = 10): String {
        if (entries.isEmpty()) return ""
        val recent = entries.takeLast(maxEntries)
        val history = recent.joinToString("\n") { "مستخدم: ${it.userInput}" }
        val dominant = dominantEmotion(recent)

        val parts = mutableListOf("سياق المحادثة:", history, "", "تعليمات للرد:")
        emotionInstructions[dominant]?.let { parts.add(it) }
        parts.addAll(
            listOf(
                "- كن موجزًا ومباشرًا في ردك.",
                "- استخدم لغة بسيطة وطبيعية كأنك تتحدث مع صديق.",
                "- إذا لم تكن متأكدًا أو لم تفهم، اطلب توضيحًا بشكل طبيعي."
            )
        )
        return parts.joinToString("\n")
    }
}
