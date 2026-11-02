package com.nexus.agent.domain.tool

/**
 * Deterministic, read-only presentation of actual tool output when an LLM is
 * unavailable. Sources are untrusted display data, never instructions or proof
 * that a requested external action was performed.
 */
object ProviderFallbackText {
    const val MISSING_KNOWLEDGE_TOPIC =
        "حدد موضوع البحث في قاعدة المعرفة، مثلاً: «ابحث في قاعدة المعرفة عن إعدادات NEXUS»."

    const val KNOWLEDGE_EMPTY =
        "اكتمل البحث في قاعدة المعرفة، ولم أجد نتائج مطابقة."

    const val KNOWLEDGE_FAILURE =
        "تعذر البحث في قاعدة المعرفة حالياً. تحقق من اتصال Second Brain وإعداداته."

    const val GENERIC_UNAVAILABLE =
        "تعذر توليد الإجابة عبر مزود الذكاء الاصطناعي حالياً. " +
            "قد يكون الرصيد غير كافٍ أو حدث عطل اتصال. " +
            "محادثاتك محفوظة، ويمكنك متابعة البحث في قاعدة المعرفة وقراءة Gmail دون DeepSeek."

    fun unavailable(code: String?): String = when (code) {
        "quota", "api_402" ->
            "رصيد DeepSeek غير كافٍ حالياً. لم أفقد المحادثة، " +
                "ويمكنك متابعة البحث في قاعدة المعرفة وقراءة Gmail دون DeepSeek."
        "no_key", "auth" ->
            "مفتاح مزود الذكاء الاصطناعي غير متاح أو غير صالح. " +
                "الأدوات المستقلة عنه ما زالت قابلة للاستخدام."
        "rate" ->
            "تجاوز مزود الذكاء الاصطناعي حد الطلبات مؤقتاً. " +
                "ما زال بإمكانك استخدام البحث والأدوات المستقلة عنه."
        else -> GENERIC_UNAVAILABLE
    }

    /**
     * At most eight real sources, each clipped to 2,000 characters. Never
     * generate an unsupported summary or completion of absent source content.
     */
    fun renderSources(
        sources: List<ToolSource>,
        introduction: String
    ): String {
        if (sources.isEmpty()) return introduction
        val items = sources.take(8).mapIndexed { index, source ->
            val heading = source.title.ifBlank {
                source.file.ifBlank { "مصدر" }
            }.take(160)
            val excerpt = source.snippet.trim().take(2_000)
                .ifBlank { "(لا يوجد مقتطف متاح)" }
            (index + 1).toString() + ". " + heading + "\n" + excerpt
        }
        val tail = if (sources.size > 8) "\n\nتم عرض أول 8 مصادر فقط." else ""
        return introduction + "\n\n" + items.joinToString("\n\n") + tail
    }
}
