package com.nexus.agent.domain.safety

import com.nexus.agent.domain.culture.CultureContext
import com.nexus.agent.domain.emotion.EmotionAnalysis
import com.nexus.agent.domain.emotion.EmotionAnalyzer

/**
 * The single safety policy applied to EVERY user message before it reaches the model/tool
 * layer. It owns three checks, in order:
 *
 *  1. Active-block enforcement — a session that has been blocked (after repeated violations)
 *     rejects every message, even a benign one, until the block expires.
 *  2. Harmful-intent gate ([EmotionAnalyzer]) — blocks the request and escalates the
 *     [UserBehaviorGuard].
 *  3. Sensitive-topic gate ([SensitiveTopicDetector]) — returns a culturally-adapted refusal.
 *
 * Extracting this into a pure domain component guarantees the legacy and agentic execution
 * paths share the exact same policy (no path can bypass it).
 */
class SafetyPolicy(
    private val emotionAnalyzer: EmotionAnalyzer,
    private val behaviorGuard: UserBehaviorGuard,
    private val intentEvaluator: IntentEvaluator,
    private val cultureContext: CultureContext,
    private val blockedMessage: String
) {

    sealed interface Decision {
        data object Allow : Decision

        /** The message must not reach the model/tool; [reply] is shown locally instead. */
        data class Reject(val reply: String) : Decision
    }

    suspend fun evaluate(sessionId: String, text: String): Decision {
        // 1) Enforce an active block FIRST — no message may proceed while blocked.
        if (behaviorGuard.isUserBlocked(sessionId)) {
            return Decision.Reject(blockedMessage)
        }

        // 2) Harmful-intent gate, escalating repeated violations.
        when (val analysis = emotionAnalyzer.analyze(text.trim())) {
            is EmotionAnalysis.Blocked -> {
                val reply = when (behaviorGuard.registerBadIntent(sessionId)) {
                    is UserBehaviorGuard.BehaviorDecision.Blocked -> blockedMessage
                    else -> analysis.localResponse
                }
                return Decision.Reject(reply)
            }
            EmotionAnalysis.Passed -> Unit
        }

        // 3) Sensitive-topic gate (religion/provocation/personal).
        SensitiveTopicDetector.detect(text.trim())?.let { category ->
            val result = intentEvaluator.handleSensitiveContent(sessionId, category, cultureContext)
            return Decision.Reject(if (result.blocked) blockedMessage else result.reply)
        }

        return Decision.Allow
    }
}
