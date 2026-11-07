package com.octopmobile.app.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octopmobile.app.R
import com.octopmobile.app.data.remote.dto.TodayTrackingDto
import com.octopmobile.app.data.remote.dto.TodayTrackingPhaseDto
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json

private val TrackingJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private val TrackingDateFormatter = DateTimeFormatter.ofPattern("M/d")
private val TrackingTimeFormatter = DateTimeFormatter.ofPattern("M/d HH:mm")

internal enum class TodayTrackingPhaseState {
    DONE,
    ACTIVE,
    UPCOMING,
}

internal fun parseTodayTracking(json: String?): TodayTrackingDto? {
    if (json.isNullOrBlank()) return null
    return runCatching {
        TrackingJson.decodeFromString(TodayTrackingDto.serializer(), json)
    }.getOrNull()?.takeIf { it.stage.isNotBlank() }
}

internal fun TodayTrackingPhaseDto.resolvedState(
    nowMillis: Long,
    currentStage: String,
    trackingStatus: String,
): TodayTrackingPhaseState {
    when (state) {
        "done" -> return TodayTrackingPhaseState.DONE
        "active" -> return TodayTrackingPhaseState.ACTIVE
        "upcoming" -> return TodayTrackingPhaseState.UPCOMING
    }
    val start = startsAt.asEpochMillis()
    val end = endsAt.asEpochMillis()
    return when {
        end != null && nowMillis > end -> TodayTrackingPhaseState.DONE
        start != null && nowMillis < start -> TodayTrackingPhaseState.UPCOMING
        start != null && (end == null || nowMillis <= end) -> TodayTrackingPhaseState.ACTIVE
        label == currentStage -> TodayTrackingPhaseState.ACTIVE
        trackingStatus == "completed" -> TodayTrackingPhaseState.DONE
        else -> TodayTrackingPhaseState.UPCOMING
    }
}

internal fun TodayTrackingPhaseDto.dateRangeLabel(zoneId: ZoneId = ZoneId.systemDefault()): String {
    val start = startsAt.asEpochMillis()?.let {
        Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate()
    }
    val end = endsAt.asEpochMillis()?.let {
        Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate()
    }
    return when {
        start != null && end != null -> {
            if (start == end) {
                start.format(TrackingDateFormatter)
            } else {
                "${start.format(TrackingDateFormatter)}–${end.format(TrackingDateFormatter)}"
            }
        }
        start != null -> "从 ${start.format(TrackingDateFormatter)}"
        end != null -> "至 ${end.format(TrackingDateFormatter)}"
        else -> ""
    }
}

internal fun Long?.asEpochMillis(): Long? = this?.let {
    if (it > 100_000_000_000L) it else it * 1000
}

internal fun trackingCheckLabel(
    epochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String = Instant.ofEpochMilli(epochMillis).atZone(zoneId).format(TrackingTimeFormatter)

@Composable
internal fun TodayTrackingPanel(
    tracking: TodayTrackingDto,
    accent: Color,
    nowMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
) {
    val phaseAccent = todayTrackingAccent(tracking, accent)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.025f),
    ) {
        Column(modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp)) {
            if (tracking.phases.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    tracking.phases.take(6).forEach { phase ->
                        TrackingPhaseCell(
                            phase = phase,
                            accent = phaseAccent,
                            nowMillis = nowMillis,
                            tracking = tracking,
                            zoneId = zoneId,
                        )
                    }
                }
            }

            tracking.progress?.let { progress ->
                if (tracking.phases.isNotEmpty()) Spacer(Modifier.height(11.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = tracking.progressLabel
                            ?: stringResource(R.string.today_tracking_progress),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "$progress%",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = phaseAccent,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((progress.coerceIn(0, 100) / 100f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(50))
                            .background(phaseAccent),
                    )
                }
            }

            if (tracking.metrics.isNotEmpty()) {
                Spacer(Modifier.height(11.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    tracking.metrics.take(3).forEach { metric ->
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(11.dp),
                            color = phaseAccent.copy(alpha = 0.08f),
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp)) {
                                Text(
                                    text = metric.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = metric.value,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                    color = phaseAccent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            if (tracking.missing.isNotEmpty()) {
                Spacer(Modifier.height(11.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MissingTrackingColor.copy(alpha = 0.09f),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
                        Text(
                            text = stringResource(R.string.today_tracking_missing),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = MissingTrackingColor,
                        )
                        Spacer(Modifier.height(4.dp))
                        tracking.missing.take(4).forEach { item ->
                            Text(
                                text = "· $item",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            val footer = listOfNotNull(
                tracking.nextMilestone?.takeIf(String::isNotBlank)?.let {
                    stringResource(R.string.today_tracking_next_milestone, it)
                },
                tracking.nextCheckAt.asEpochMillis()?.let {
                    stringResource(
                        R.string.today_tracking_check_at,
                        trackingCheckLabel(it, zoneId),
                    )
                },
            )
            if (footer.isNotEmpty()) {
                Spacer(Modifier.height(9.dp))
                Text(
                    text = footer.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TrackingPhaseCell(
    phase: TodayTrackingPhaseDto,
    accent: Color,
    nowMillis: Long,
    tracking: TodayTrackingDto,
    zoneId: ZoneId,
) {
    val state = phase.resolvedState(
        nowMillis = nowMillis,
        currentStage = tracking.stage,
        trackingStatus = tracking.status,
    )
    val active = state == TodayTrackingPhaseState.ACTIVE
    val label = when (state) {
        TodayTrackingPhaseState.DONE -> stringResource(R.string.today_tracking_phase_done)
        TodayTrackingPhaseState.ACTIVE -> stringResource(R.string.today_tracking_phase_active)
        TodayTrackingPhaseState.UPCOMING -> stringResource(R.string.today_tracking_phase_upcoming)
    }
    Column(
        modifier = Modifier
            .width(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (active) accent.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
            )
            .padding(horizontal = 9.dp, vertical = 8.dp),
    ) {
        Text(
            text = phase.label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = if (active) accent else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val range = phase.dateRangeLabel(zoneId)
        if (range.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = range,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

internal fun todayTrackingAccent(tracking: TodayTrackingDto, fallback: Color): Color = when {
    tracking.status == "waiting_user" -> MissingTrackingColor
    tracking.status == "completed" -> CompletedTrackingColor
    else -> fallback
}

private val MissingTrackingColor = Color(0xFFC47B1E)
private val CompletedTrackingColor = Color(0xFF2D9664)
