package com.nexus.agent.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nexus.agent.R

/**
 * NEXUS X presentation layer. No tool execution, credentials, networking, or chat state lives here.
 * All controls receive their actual handlers from ChatScreen.
 */
@Composable
internal fun NexusXHeader(
    engineLabel: String,
    conversationCount: Int,
    onNewChat: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.nexus_logo_premium_dark),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "NEXUS X",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "مساعدك الذكي الشخصي",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onHistory, modifier = Modifier.size(42.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.List,
                        contentDescription = "سجل المحادثات ($conversationCount)"
                    )
                }
                IconButton(onClick = onNewChat, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Default.Add, contentDescription = "محادثة جديدة")
                }
                IconButton(onClick = onSettings, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Default.Settings, contentDescription = "الإعدادات")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Box(
                    Modifier.size(7.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Text(
                    text = "المحرك المحدد: $engineLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "$conversationCount محادثة محفوظة",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun NexusXWelcome(onSuggestionSelected: (String) -> Unit) {
    val suggestions = listOf(
        "نظّم مهامي" to "ساعدني على تنظيم أولوياتي ومهامي اليوم",
        "ابحث في معرفتي" to "ابحث في قاعدة المعرفة عن ",
        "اكتب رسالة" to "ساعدني في صياغة رسالة احترافية إلى "
    )
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(24.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.nexus_logo_premium_dark),
                contentDescription = null,
                modifier = Modifier.padding(12.dp).size(60.dp).clip(RoundedCornerShape(18.dp))
            )
        }
        Text(
            text = "بماذا نبدأ اليوم؟",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            text = "اسأل، خطط، أو ابدأ مهمة. تبقى القرارات الحساسة بيدك.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(7.dp))
        suggestions.forEach { (label, prompt) ->
            Surface(
                shape = RoundedCornerShape(17.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(17.dp))
                    .clickable { onSuggestionSelected(prompt) }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "↗",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
        Text(
            text = "تُضاف الاقتراحات إلى حقل الرسالة للمراجعة قبل الإرسال.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

internal enum class NexusXSettingsSection(val title: String, val description: String) {
    SERVICES("الخدمات", "ربط Google والصلاحيات المحددة لكل خدمة"),
    AI("الذكاء", "DeepSeek وHermes ومفاتيح الاتصال"),
    KNOWLEDGE("الذاكرة", "Neon وقاعدة المعرفة والنسخ الاحتياطي"),
    PERSONA("الشخصية", "اللهجة والنبرة ومشاعر"),
    ADVANCED("متقدم", "التعليمات والخصوصية والتشخيص")
}

@Composable
internal fun NexusXSettingsNavigation(
    selected: NexusXSettingsSection,
    onSelect: (NexusXSettingsSection) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NexusXSettingsSection.entries.forEach { section ->
                FilterChip(
                    selected = selected == section,
                    onClick = { onSelect(section) },
                    label = { Text(section.title) },
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }
        Text(
            text = selected.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
