package com.nexus.agent.domain.safety

import com.nexus.agent.domain.culture.Region
import com.nexus.agent.domain.culture.ToneSensitivity

/** Categories of sensitive content that get a curated, culturally-adapted neutral reply. */
enum class NeutralCategory(val id: String) {
    RELIGION("religion"),
    PROVOCATION("provocation"),
    PERSONAL_QUESTION("personal_question");

    companion object {
        fun fromId(id: String?): NeutralCategory? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Curated neutral replies for sensitive topics, adapted per region and tone sensitivity.
 * Ported from Mashaaer's `neutral_response_handler.py` RESPONSES dict. A lookup falls back
 * region → Gulf → neutral tone so callers always get a graceful reply.
 */
object NeutralResponses {

    private val responses: Map<NeutralCategory, Map<Region, Map<ToneSensitivity, String>>> = mapOf(
        NeutralCategory.RELIGION to mapOf(
            Region.GULF to mapOf(
                ToneSensitivity.SOFT to "أنا مصممة لأساعدك مهما كانت خلفيتك، يا طيب 🙏",
                ToneSensitivity.NEUTRAL to "أنا هنا لمساعدتك دون التطرق لمواضيع دينية.",
                ToneSensitivity.ASSERTIVE to "لا يمكنني الدخول في هذا الموضوع. ننتقل لشيء آخر؟"
            ),
            Region.EGYPT to mapOf(
                ToneSensitivity.SOFT to "يا فندم، إحنا هنا نحترم الكل من غير جدال ❤️",
                ToneSensitivity.NEUTRAL to "أنا هنا علشان أساعد، مش علشان أدخل في معتقدات.",
                ToneSensitivity.ASSERTIVE to "الموضوع ده مش مناسب للنقاش هنا، نركز على اللي يهمك؟"
            ),
            Region.LEVANT to mapOf(
                ToneSensitivity.SOFT to "أنا بفضّل نضل نحكي باحترام مهما كان الموضوع ✨",
                ToneSensitivity.NEUTRAL to "خلينا نكمل حديثنا بعيد عن الدين.",
                ToneSensitivity.ASSERTIVE to "هيك نوع من الحوار مش ضمن اختصاصي."
            ),
            Region.IRAQ to mapOf(
                ToneSensitivity.SOFT to "حبيبي، مواضيع الدين حساسة، خلينا نحجي بشي ثاني ينفعك 🌟",
                ToneSensitivity.NEUTRAL to "أفضل ما أتطرق لمواضيع دينية، خلينا نركز على مساعدتك.",
                ToneSensitivity.ASSERTIVE to "هاي المواضيع ما أكدر أخوض بيها، شنو ممكن أساعدك بشي ثاني؟"
            ),
            Region.SUDAN to mapOf(
                ToneSensitivity.SOFT to "يا صاحبي، خلينا في كلام بعيد عن المساس بالدين ومشاعر الناس 🤲",
                ToneSensitivity.NEUTRAL to "المواضيع الدينية حساسة، ممكن نتكلم في محور تاني؟",
                ToneSensitivity.ASSERTIVE to "ما بقدر أتناقش في الموضوع دا. عايز نتكلم في شنو تاني؟"
            ),
            Region.YEMEN to mapOf(
                ToneSensitivity.SOFT to "يا غالي، دعنا نتحدث بما ينفع بعيداً عن المواضيع الحساسة 🌿",
                ToneSensitivity.NEUTRAL to "المواضيع الدينية خارج نطاق الحديث هنا.",
                ToneSensitivity.ASSERTIVE to "مش مسموح لي أخوض في هذا النقاش. نقدر نغير الموضوع؟"
            )
        ),
        NeutralCategory.PROVOCATION to mapOf(
            Region.GULF to mapOf(
                ToneSensitivity.SOFT to "أنا موجودة علشان أساعد، خل نبتعد عن الأسلوب هذا 💫",
                ToneSensitivity.NEUTRAL to "خلينا نحافظ على حوار محترم.",
                ToneSensitivity.ASSERTIVE to "أسلوبك غير مناسب، وسأتوقف إذا استمريت به."
            ),
            Region.LEVANT to mapOf(
                ToneSensitivity.SOFT to "شو ما كان قصدك، بحب نضل نحكي باحترام 🤝",
                ToneSensitivity.NEUTRAL to "خلينا نغير النبرة شوي.",
                ToneSensitivity.ASSERTIVE to "إذا ما تغيّر الأسلوب، ممكن أوقف الحديث."
            ),
            Region.EGYPT to mapOf(
                ToneSensitivity.SOFT to "ياريت نخلي الكلام بأسلوب حلو، عشان نقدر نتواصل أحسن 💕",
                ToneSensitivity.NEUTRAL to "الأسلوب ده مش هيساعدنا نتكلم بشكل مناسب.",
                ToneSensitivity.ASSERTIVE to "لو استمر الكلام كده، هضطر أنهي المحادثة."
            ),
            Region.IRAQ to mapOf(
                ToneSensitivity.SOFT to "خلينا نحجي بأسلوب أحلى، هيچي أحسن للكل 🌼",
                ToneSensitivity.NEUTRAL to "أحب نحافظ على أسلوب محترم بالكلام.",
                ToneSensitivity.ASSERTIVE to "هذا الأسلوب ما يصير، إذا استمريت راح أوقف الحوار."
            ),
            Region.SUDAN to mapOf(
                ToneSensitivity.SOFT to "يا زول، خلينا نتكلم بأدب وذوق أحسن 🙌",
                ToneSensitivity.NEUTRAL to "الكلام دا ما بيساعد، خلينا نغير الأسلوب.",
                ToneSensitivity.ASSERTIVE to "إذا استمريت بالطريقة دي، حنوقف الكلام هنا."
            ),
            Region.YEMEN to mapOf(
                ToneSensitivity.SOFT to "يا طيب، الكلام الطيب أفضل للجميع، خلينا نتحاور باحترام 🤝",
                ToneSensitivity.NEUTRAL to "دعنا نغير أسلوب الحديث لنتمكن من التواصل بشكل أفضل.",
                ToneSensitivity.ASSERTIVE to "هذا الأسلوب غير مقبول، إما أن نغيره أو نتوقف عن الحديث."
            )
        ),
        NeutralCategory.PERSONAL_QUESTION to mapOf(
            Region.GULF to mapOf(
                ToneSensitivity.SOFT to "يا طيب، أنا هنا لمساعدتك وليس للحديث عن نفسي 💙",
                ToneSensitivity.NEUTRAL to "تركيزي هو على مساعدتك، وليس الحديث عن معلومات شخصية.",
                ToneSensitivity.ASSERTIVE to "هذه معلومات شخصية لا يمكنني مشاركتها. هل يمكنني مساعدتك بشيء آخر؟"
            ),
            Region.EGYPT to mapOf(
                ToneSensitivity.SOFT to "أنا هنا عشان أساعدك مش عشان أتكلم عن نفسي، ياريت نركز على اللي يفيدك 💗",
                ToneSensitivity.NEUTRAL to "دي معلومات مش متاحة، ممكن نتكلم في حاجة تانية؟",
                ToneSensitivity.ASSERTIVE to "مش هقدر أجاوب على الأسئلة الشخصية دي. عندك استفسار تاني؟"
            ),
            Region.LEVANT to mapOf(
                ToneSensitivity.SOFT to "حبيبي، خلينا نحكي بشي مفيد إلك، مش عني أنا 💯",
                ToneSensitivity.NEUTRAL to "هي معلومات خاصة مش متاحة للمشاركة.",
                ToneSensitivity.ASSERTIVE to "ما بقدر أشارك هيك معلومات. في شي تاني ممكن أساعدك فيه؟"
            ),
            Region.IRAQ to mapOf(
                ToneSensitivity.SOFT to "حبيبي، آني هنا أساعدك، مو أحجي عن نفسي 🌟",
                ToneSensitivity.NEUTRAL to "هاي معلومات شخصية ما أكدر أشاركها.",
                ToneSensitivity.ASSERTIVE to "ما يصير أحجي بهالمعلومات. شتحب نحجي بموضوع ثاني؟"
            ),
            Region.SUDAN to mapOf(
                ToneSensitivity.SOFT to "أنا هنا عشان أفيدك، مش عشان أتكلم عن نفسي، يا صاحبي 🙂",
                ToneSensitivity.NEUTRAL to "دي معلومات ما بقدر أشاركها، ممكن نتكلم في حاجة تانية؟",
                ToneSensitivity.ASSERTIVE to "الأسئلة الشخصية دي ما عندي إجابة ليها. نغير الموضوع؟"
            ),
            Region.YEMEN to mapOf(
                ToneSensitivity.SOFT to "أنا هنا لخدمتك، مش للكلام عن نفسي يا غالي 🤲",
                ToneSensitivity.NEUTRAL to "هذي معلومات شخصية ما أقدر أشاركها معك.",
                ToneSensitivity.ASSERTIVE to "ما يمكنني الإجابة على هذه الأسئلة الشخصية. نتكلم في موضوع آخر؟"
            )
        )
    )

    /** Returns the curated reply, falling back region → Gulf → neutral tone → generic. */
    fun neutralResponse(category: NeutralCategory, region: Region, tone: ToneSensitivity): String =
        responses[category]?.get(region)?.get(tone)
            ?: responses[category]?.get(Region.GULF)?.get(tone)
            ?: responses[category]?.get(Region.GULF)?.get(ToneSensitivity.NEUTRAL)
            ?: DEFAULT_NEUTRAL

    private const val DEFAULT_NEUTRAL = "أنا هنا لمساعدتك بطريقة آمنة وبنّاءة."
}
