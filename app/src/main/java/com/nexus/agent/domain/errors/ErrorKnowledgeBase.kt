package com.nexus.agent.domain.errors

/** Categories of runtime errors. Ported from Mashaaer's ErrorCategory enum. */
enum class ErrorCategory(val id: String) {
    DATABASE("DATABASE"),
    NETWORK("NETWORK"),
    API("API"),
    AUTH("AUTH"),
    INPUT("INPUT"),
    INTEGRATION("INTEGRATION"),
    PERFORMANCE("PERFORMANCE"),
    INTERNAL("INTERNAL"),
    UNCATEGORIZED("UNCATEGORIZED")
}

/** A single self-healing entry: how to detect an error and what to suggest. */
data class ErrorSolution(
    val code: String,
    val description: String,
    val probableCauses: List<String>,
    val suggestedSolutions: List<String>,
    val category: ErrorCategory,
    val patterns: List<Regex>,
    val autoRepairable: Boolean,
    val repairStrategy: String? = null
)

/**
 * Maps error messages to known causes and solutions (self-healing). Ported from Mashaaer's
 * `error_knowledge_base.py` ERROR_KNOWLEDGE_BASE. [matchError] returns the first entry whose
 * patterns match the message; [categoryOf] falls back to UNCATEGORIZED.
 */
object ErrorKnowledgeBase {

    private val entries: List<ErrorSolution> = listOf(
        ErrorSolution(
            code = "ERR_DB_CONNECTION_FAILED",
            description = "فشل الاتصال بقاعدة البيانات",
            probableCauses = listOf(
                "مشكلة اتصال الشبكة", "تحميل زائد على الخادم",
                "بيانات اعتماد غير صحيحة", "إعدادات خاطئة لسلسلة الاتصال"
            ),
            suggestedSolutions = listOf(
                "إعادة محاولة الاتصال", "التحقق من حالة خادم قاعدة البيانات", "التحقق من بيانات الاعتماد"
            ),
            category = ErrorCategory.DATABASE,
            patterns = listOf(
                Regex("connection to database", RegexOption.IGNORE_CASE),
                Regex("cannot connect", RegexOption.IGNORE_CASE),
                Regex("unable to connect", RegexOption.IGNORE_CASE),
                Regex("connection failed", RegexOption.IGNORE_CASE)
            ),
            autoRepairable = true,
            repairStrategy = "database_reconnect"
        ),
        ErrorSolution(
            code = "ERR_API_TIMEOUT",
            description = "انتهت مهلة طلب API",
            probableCauses = listOf("تأخير الشبكة", "تحميل زائد على خادم API", "طلب معقد يستغرق وقتًا طويلاً"),
            suggestedSolutions = listOf(
                "إعادة المحاولة مع تأخير متزايد", "التحقق من حالة خادم API", "تبسيط الطلب إذا أمكن"
            ),
            category = ErrorCategory.API,
            patterns = listOf(
                Regex("request timeout", RegexOption.IGNORE_CASE),
                Regex("timed out", RegexOption.IGNORE_CASE),
                Regex("timeout", RegexOption.IGNORE_CASE),
                Regex("انتهت المهلة")
            ),
            autoRepairable = true,
            repairStrategy = "retry_with_backoff"
        ),
        ErrorSolution(
            code = "ERR_API_RATE_LIMIT",
            description = "تم تجاوز حد طلبات API",
            probableCauses = listOf("عدد كبير من الطلبات في فترة قصيرة", "قيود على عدد الطلبات", "حصة API غير كافية"),
            suggestedSolutions = listOf(
                "تقليل معدل الطلبات", "تنفيذ آلية للتحكم في معدل الطلبات",
                "الانتظار قبل إعادة المحاولة", "الترقية إلى خطة أعلى"
            ),
            category = ErrorCategory.API,
            patterns = listOf(
                Regex("rate limit", RegexOption.IGNORE_CASE),
                Regex("429"),
                Regex("too many requests", RegexOption.IGNORE_CASE)
            ),
            autoRepairable = false
        ),
        ErrorSolution(
            code = "ERR_AUTH_FAILED",
            description = "فشل المصادقة",
            probableCauses = listOf("مفتاح API غير صالح", "مفتاح API منتهي الصلاحية", "بيانات اعتماد غير صحيحة"),
            suggestedSolutions = listOf("التحقق من مفتاح API", "تجديد مفتاح API"),
            category = ErrorCategory.AUTH,
            patterns = listOf(
                Regex("401"),
                Regex("unauthorized", RegexOption.IGNORE_CASE),
                Regex("authentication failed", RegexOption.IGNORE_CASE),
                Regex("invalid api key", RegexOption.IGNORE_CASE)
            ),
            autoRepairable = false
        )
    )

    /** Returns the first known solution whose patterns match [message], or null. */
    fun matchError(message: String): ErrorSolution? =
        entries.firstOrNull { entry -> entry.patterns.any { it.containsMatchIn(message) } }

    /** Categorizes an error message, defaulting to UNCATEGORIZED. */
    fun categoryOf(message: String): ErrorCategory =
        matchError(message)?.category ?: ErrorCategory.UNCATEGORIZED
}
