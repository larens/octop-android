package com.octopmobile.app.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octopmobile.app.R
import com.octopmobile.app.data.db.TodayCardEntity
import com.octopmobile.app.data.remote.dto.TodayCardActionState
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

internal data class MorningDigestItem(
    val card: TodayCardEntity,
    val preview: String,
)

internal data class MorningDigestSection(
    val titleRes: Int,
    val items: List<MorningDigestItem>,
)

internal data class MorningDigest(
    val sections: List<MorningDigestSection>,
    val cardIds: Set<String>,
) {
    val itemCount: Int get() = cardIds.size
}

/**
 * Merge the same morning's cron cards into one briefing.
 *
 * Classification is deterministic and based on the already-projected card kind,
 * title and pending-mail marker. It never asks a model to label the user's day.
 */
internal fun buildMorningDigest(
    cards: List<TodayCardEntity>,
    nowMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): MorningDigest? {
    val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
    val eligible = cards.filter { card ->
        card.kind in MorningDigestKinds &&
            card.lifecycle != "dismissed" &&
            card.isMorningDigestSource(today, zoneId)
    }
    if (eligible.size < 2) return null

    val sorted = eligible.sortedWith(
        compareBy<TodayCardEntity>(TodayCardEntity::occurredAt)
            .thenBy(TodayCardEntity::title),
    )
    val items = sorted.map { card ->
        MorningDigestItem(
            card = card,
            preview = todayCardSummaryPreview(card.summary)
                .lineSequence()
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                .orEmpty(),
        )
    }
    val sections = DigestSectionOrder.mapNotNull { section ->
        val sectionItems = items.filter { digestSection(it.card) == section }
        if (sectionItems.isEmpty()) {
            null
        } else {
            MorningDigestSection(titleRes = section.titleRes, items = sectionItems)
        }
    }
    return MorningDigest(
        sections = sections,
        cardIds = sorted.map(TodayCardEntity::id).toSet(),
    )
}

private val MorningDigestKinds = setOf("briefing", "inbox")

private fun TodayCardEntity.isMorningDigestSource(
    today: LocalDate,
    zoneId: ZoneId,
): Boolean {
    val occurred = Instant.ofEpochMilli(occurredAt).atZone(zoneId)
    if (occurred.toLocalDate() == today) {
        return occurred.toLocalTime().isInMorningWindow()
    }
    // A "tomorrow plan" produced the night before is still the current
    // morning's agenda; merging it avoids two near-identical agenda cards.
    return occurred.toLocalDate() == today.minusDays(1) &&
        occurred.toLocalTime() >= LocalTime.of(18, 0) &&
        title.contains("明日")
}

private fun LocalTime.isInMorningWindow(): Boolean =
    this >= LocalTime.of(4, 0) && this < LocalTime.of(12, 0)

private enum class DigestSection(val titleRes: Int) {
    MUST_DO(R.string.today_digest_must_do),
    CAN_DRAFT(R.string.today_digest_can_draft),
    LATER(R.string.today_digest_later),
}

private val DigestSectionOrder = listOf(
    DigestSection.MUST_DO,
    DigestSection.CAN_DRAFT,
    DigestSection.LATER,
)

private fun digestSection(card: TodayCardEntity): DigestSection {
    val pendingMail = card.kind == "inbox" && todayCardHasPendingMail(card.summary)
    val needsConfirmation =
        card.actionState == TodayCardActionState.WAITING_CONFIRMATION ||
            card.riskLevel == "A3" ||
            card.riskLevel == "A4"
    if (pendingMail || needsConfirmation) return DigestSection.MUST_DO
    val title = card.title
    if (
        card.kind == "inbox" ||
        card.kind == "calendar" ||
        listOf("日程", "安排", "邮件", "阅读", "准备").any(title::contains)
    ) {
        return DigestSection.CAN_DRAFT
    }
    return DigestSection.LATER
}

@Composable
internal fun MorningBriefingCard(
    digest: MorningDigest,
    onOpenCard: (TodayCardEntity) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = accent.copy(alpha = 0.12f)) {
                    Icon(
                        imageVector = Icons.Filled.WbSunny,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.padding(8.dp).size(18.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.today_morning_digest_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(
                            R.string.today_morning_digest_subtitle,
                            digest.itemCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            digest.sections.forEach { section ->
                Text(
                    text = stringResource(section.titleRes),
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                )
                section.items.forEach { item ->
                    DigestRow(item = item, accent = accent, onClick = { onOpenCard(item.card) })
                }
            }
        }
    }
}

@Composable
private fun DigestRow(
    item: MorningDigestItem,
    accent: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(accent.copy(alpha = 0.55f), CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = item.card.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.preview.isNotBlank()) {
                Text(
                    text = item.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
