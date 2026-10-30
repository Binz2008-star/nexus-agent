package com.nexus.agent.domain.safety

import com.nexus.agent.domain.culture.CultureContext

/**
 * Result of handling sensitive content. Ported from Mashaaer's `intent_evaluator.py`
 * `handle_sensitive_content` return shape.
 */
data class SensitiveContentResult(
    val reply: String,
    val blocked: Boolean,
    val warningCount: Int,
    val remainingBlockTimeMillis: Long,
    val intentDetected: NeutralCategory
)

/**
 * Coordinates the safety layer for sensitive content: records the violation with
 * [UserBehaviorGuard] and returns a culturally-adapted neutral reply plus the escalation
 * state. This is the "glue" the chat flow calls after [EmotionAnalyzer] flags a message.
 */
class IntentEvaluator(
    private val behaviorGuard: UserBehaviorGuard = UserBehaviorGuard()
) {

    fun handleSensitiveContent(
        sessionId: String,
        category: NeutralCategory,
        context: CultureContext
    ): SensitiveContentResult {
        val reply = NeutralResponses.neutralResponse(category, context.region, context.toneSensitivity)
        return when (val decision = behaviorGuard.registerBadIntent(sessionId)) {
            is UserBehaviorGuard.BehaviorDecision.Allowed ->
                SensitiveContentResult(reply, blocked = false, warningCount = 0, remainingBlockTimeMillis = 0, intentDetected = category)
            is UserBehaviorGuard.BehaviorDecision.Warned ->
                SensitiveContentResult(reply, blocked = false, warningCount = decision.warningCount, remainingBlockTimeMillis = 0, intentDetected = category)
            is UserBehaviorGuard.BehaviorDecision.Blocked ->
                SensitiveContentResult(reply, blocked = true, warningCount = 0, remainingBlockTimeMillis = decision.remainingMillis, intentDetected = category)
        }
    }
}
