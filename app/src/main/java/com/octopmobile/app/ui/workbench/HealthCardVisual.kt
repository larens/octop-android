package com.octopmobile.app.ui.workbench

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.max
import kotlin.math.roundToInt

@Serializable
internal data class HealthVisualization(
    @SerialName("w") val window: String = "",
    @SerialName("m") val metrics: List<HealthMetricVisual> = emptyList(),
)

@Serializable
internal data class HealthMetricVisual(
    @SerialName("k") val key: String = "",
    @SerialName("v") val value: Double? = null,
    @SerialName("d") val display: String = "",
    @SerialName("u") val unit: String = "",
    @SerialName("t") val target: Double? = null,
    @SerialName("s") val series: List<Double?> = emptyList(),
    @SerialName("c") val chart: String = "bar",
)

internal enum class HealthChartMode {
    RING,
    BAR,
    COLUMNS,
    LINE,
    RATIO,
}

internal fun healthChartMode(chart: String): HealthChartMode = when (chart.lowercase()) {
    "ring" -> HealthChartMode.RING
    "bar" -> HealthChartMode.BAR
    "line" -> HealthChartMode.LINE
    "ratio" -> HealthChartMode.RATIO
    else -> HealthChartMode.COLUMNS
}

internal fun healthPrimaryTrendHeight(chart: String): Dp = when (healthChartMode(chart)) {
    HealthChartMode.RATIO -> 14.dp
    else -> 46.dp
}

internal fun healthMetricColor(key: String): Color = when (key) {
    "steps" -> Color(0xFF36C994)
    "sleep" -> Color(0xFF8B82F6)
    "deep_sleep" -> Color(0xFF5D7CE2)
    "deep_ratio" -> Color(0xFF7069D8)
    "awake" -> Color(0xFFE4A84C)
    "wake_time" -> Color(0xFF9A7BD8)
    "standing" -> Color(0xFF1FB9C7)
    "sedentary" -> Color(0xFFD8912F)
    "heart" -> Color(0xFFF26868)
    "resting_heart" -> Color(0xFFE35484)
    "calories" -> Color(0xFFF28A3D)
    "exercise_minutes" -> Color(0xFF2CA9C8)
    "activity_minutes" -> Color(0xFF2F9FD4)
    "oxygen" -> Color(0xFF32A9E8)
    "stress" -> Color(0xFFE88A3C)
    else -> Color(0xFF4FAF8C)
}

internal fun parseHealthVisualization(summary: String): HealthVisualization? {
    val body = parseTodayCardSummary(summary)
        .firstOrNull { it.heading == "健康可视化" }
        ?.body
        ?: return null
    return runCatching {
        HealthVisualJson.decodeFromString(HealthVisualization.serializer(), body)
    }.getOrNull()?.takeIf { it.metrics.isNotEmpty() }
}

private data class HealthMetricStyle(
    val color: Color,
    val container: Color,
    val glyph: String,
)

private fun healthMetricStyle(metric: HealthMetricVisual): HealthMetricStyle {
    val color = healthMetricColor(metric.key)
    return HealthMetricStyle(
        color = color,
        container = color.copy(alpha = 0.085f),
        glyph = healthMetricGlyph(metric.key),
    )
}

@Composable
internal fun HealthVisualPanel(
    visualization: HealthVisualization,
    accent: Color,
) {
    val primary = visualization.metrics.firstOrNull() ?: return
    val gridMetrics = visualization.metrics
        .drop(1)
        .filterNot { it.key == "stress" }
    val auxiliaryMetric = visualization.metrics.firstOrNull { it.key == "stress" }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f),
                shape = RoundedCornerShape(18.dp),
            ),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.20f),
    ) {
        Column(
            modifier = Modifier.padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (
                healthChartMode(primary.chart) == HealthChartMode.RING &&
                (primary.target ?: 0.0) > 0.0
            ) {
                HealthPrimaryRing(primary)
            } else {
                HealthPrimaryTrend(primary, fallbackAccent = accent)
            }
            gridMetrics
                .chunked(2)
                .forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Max),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { metric ->
                            HealthMetricTile(
                                metric = metric,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                        }
                        if (row.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            auxiliaryMetric?.let { HealthAuxiliaryMetric(it) }
        }
    }
}

@Composable
private fun HealthMetricBadge(
    metric: HealthMetricVisual,
    size: Dp = 28.dp,
) {
    val style = healthMetricStyle(metric)
    Box(
        modifier = Modifier
            .size(size)
            .background(style.color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = style.glyph,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
        )
    }
}

@Composable
private fun HealthMetricHeading(
    metric: HealthMetricVisual,
    badgeSize: Dp = 28.dp,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HealthMetricBadge(metric, size = badgeSize)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = healthMetricLabel(metric.key),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            metric.target?.takeIf { it > 0 }?.let { target ->
                Text(
                    text = healthTargetText(metric, target),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HealthPrimaryRing(metric: HealthMetricVisual) {
    val target = metric.target ?: return
    val value = metric.value ?: 0.0
    val progress = (value / target).coerceIn(0.0, 1.0).toFloat()
    val style = healthMetricStyle(metric)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(78.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(78.dp)) {
                    val stroke = 7.dp.toPx()
                    val inset = stroke / 2
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawArc(
                        color = style.color.copy(alpha = 0.15f),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    drawArc(
                        color = style.color,
                        startAngle = -90f,
                        sweepAngle = 360f * progress,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = metric.display,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        color = style.color,
                    )
                    Text(
                        text = healthDisplayUnit(metric),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                HealthMetricHeading(metric)
                Spacer(Modifier.height(8.dp))
                MetricTrend(metric = metric, height = 30.dp)
                HealthSampleNote(metric)
            }
        }
    }
}

@Composable
private fun HealthPrimaryTrend(
    metric: HealthMetricVisual,
    fallbackAccent: Color,
) {
    val style = healthMetricStyle(metric)
    val valueColor = if (metric.key.isBlank()) fallbackAccent else style.color
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HealthMetricBadge(metric, size = 34.dp)
            Spacer(Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = healthMetricLabel(metric.key),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = metric.display,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        color = valueColor,
                    )
                    if (healthDisplayUnit(metric).isNotBlank()) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = healthDisplayUnit(metric),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            metric.target?.takeIf { it > 0 }?.let { target ->
                Text(
                    text = healthTargetText(metric, target),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        MetricTrend(metric = metric, height = healthPrimaryTrendHeight(metric.chart))
        HealthSampleNote(metric)
    }
}

@Composable
private fun HealthMetricTile(
    metric: HealthMetricVisual,
    modifier: Modifier,
) {
    val style = healthMetricStyle(metric)
    Column(
        modifier = modifier
            .background(
                color = style.container,
                shape = RoundedCornerShape(13.dp),
            )
            .border(
                width = 1.dp,
                color = style.color.copy(alpha = 0.14f),
                shape = RoundedCornerShape(13.dp),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        HealthMetricHeading(metric, badgeSize = 24.dp)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = metric.display,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = style.color,
            )
            if (healthDisplayUnit(metric).isNotBlank()) {
                Spacer(Modifier.width(3.dp))
                Text(
                    text = healthDisplayUnit(metric),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val mode = healthChartMode(metric.chart)
        if (mode == HealthChartMode.RATIO || metric.series.size >= 2) {
            Spacer(Modifier.height(3.dp))
            MetricTrend(
                metric = metric,
                height = when (mode) {
                    HealthChartMode.RATIO -> 14.dp
                    HealthChartMode.BAR -> 32.dp
                    else -> 28.dp
                },
            )
        }
        HealthSampleNote(metric)
    }
}

@Composable
private fun HealthAuxiliaryMetric(metric: HealthMetricVisual) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.028f),
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "压力（辅助参考）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = listOf(metric.display, metric.unit)
                .filter(String::isNotBlank)
                .joinToString(" "),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HealthSampleNote(metric: HealthMetricVisual) {
    val samples = metric.series.count { it != null }
    if (samples !in 1..2 || metric.series.size < 2) return
    Spacer(Modifier.height(3.dp))
    Text(
        text = "有效样本 $samples/${metric.series.size} 天",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MetricTrend(
    metric: HealthMetricVisual,
    height: Dp,
) {
    val values = metric.series
    val mode = healthChartMode(metric.chart)
    if (
        mode != HealthChartMode.RATIO &&
        (values.size < 2 || values.all { it == null })
    ) {
        return
    }
    val color = healthMetricColor(metric.key)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
    ) {
        if (mode != HealthChartMode.RATIO) {
            drawTrendGrid(color)
        }
        when (mode) {
            HealthChartMode.RATIO -> drawRatio(metric, color)
            HealthChartMode.LINE -> drawLineTrend(values, color)
            HealthChartMode.BAR -> drawHorizontalBars(values, color)
            HealthChartMode.RING,
            HealthChartMode.COLUMNS,
            -> drawColumns(metric, values, color)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRatio(
    metric: HealthMetricVisual,
    color: Color,
) {
    val target = metric.target?.toFloat()?.takeIf { it > 0f } ?: return
    val value = metric.value?.toFloat() ?: return
    val progress = (value / target).coerceIn(0f, 1f)
    val radius = size.height / 2
    drawRoundRect(
        color = color.copy(alpha = 0.12f),
        cornerRadius = CornerRadius(radius, radius),
    )
    drawRoundRect(
        color = color,
        size = Size(size.width * progress, size.height),
        cornerRadius = CornerRadius(radius, radius),
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTrendGrid(color: Color) {
    val grid = color.copy(alpha = 0.10f)
    repeat(3) { index ->
        val y = size.height * (index + 1) / 4f
        drawLine(
            color = grid,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLineTrend(
    values: List<Double?>,
    color: Color,
) {
    val numbers = values.mapNotNull { it?.toFloat() }
    if (numbers.isEmpty()) return
    val maximum = max(numbers.maxOrNull() ?: 0f, 1f)
    val minimum = numbers.minOrNull() ?: 0f
    val span = max(maximum - minimum, 1f)
    val segments = mutableListOf<MutableList<Offset>>()
    var current = mutableListOf<Offset>()
    values.forEachIndexed { index, raw ->
        val value = raw?.toFloat()
        if (value == null) {
            if (current.size >= 2) segments += current
            current = mutableListOf()
            return@forEachIndexed
        }
        val x = size.width * index / (values.size - 1).coerceAtLeast(1)
        val y = size.height - ((value - minimum) / span * size.height * 0.76f) -
            size.height * 0.12f
        current += Offset(x, y)
    }
    if (current.isNotEmpty()) segments += current

    segments.forEach { points ->
        if (points.size >= 2) {
            val area = Path().apply {
                moveTo(points.first().x, size.height)
                points.forEach { lineTo(it.x, it.y) }
                lineTo(points.last().x, size.height)
                close()
            }
            drawPath(
                path = area,
                brush = Brush.verticalGradient(
                    colors = listOf(color.copy(alpha = 0.24f), Color.Transparent),
                ),
            )
            val line = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(
                path = line,
                color = color,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )
        } else {
            drawLine(
                color = color.copy(alpha = 0.28f),
                start = Offset(points.first().x, points.first().y),
                end = Offset(points.first().x, size.height),
                strokeWidth = 1.dp.toPx(),
            )
        }
        drawCircle(
            color = color,
            radius = 2.8.dp.toPx(),
            center = points.last(),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.78f),
            radius = 1.15.dp.toPx(),
            center = points.last(),
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawColumns(
    metric: HealthMetricVisual,
    values: List<Double?>,
    color: Color,
) {
    val numbers = values.mapNotNull { it?.toFloat() }
    val rawMinimum = numbers.minOrNull() ?: 0f
    val rawMaximum = numbers.maxOrNull() ?: 0f
    val floor = when (metric.key) {
        "oxygen" -> minOf(90f, rawMinimum - 1f)
        "heart",
        "resting_heart",
        -> max(0f, rawMinimum - 5f)
        else -> 0f
    }
    val ceiling = when (metric.key) {
        "oxygen" -> max(100f, rawMaximum)
        "heart",
        "resting_heart",
        -> max(rawMaximum + 5f, floor + 10f)
        "standing" -> max(rawMaximum, (metric.target ?: 12.0).toFloat())
        else -> max(rawMaximum, 1f)
    }
    val span = max(ceiling - floor, 1f)
    val slot = size.width / values.size
    val barWidth = slot * 0.56f
    val baseline = size.height - 1.dp.toPx()
    values.forEachIndexed { index, raw ->
        val value = raw?.toFloat()
        val trackHeight = size.height * 0.90f
        drawRoundRect(
            color = color.copy(alpha = 0.08f),
            topLeft = Offset(
                x = slot * index + (slot - barWidth) / 2,
                y = baseline - trackHeight,
            ),
            size = Size(barWidth, trackHeight),
            cornerRadius = CornerRadius(barWidth / 2, barWidth / 2),
        )
        if (value == null) return@forEachIndexed
        val ratio = ((value - floor) / span).coerceIn(0f, 1f)
        val barHeight = max(size.height * ratio * 0.96f, 2.dp.toPx())
        drawRoundRect(
            color = color.copy(alpha = if (index == values.lastIndex) 1f else 0.46f),
            topLeft = Offset(
                x = slot * index + (slot - barWidth) / 2,
                y = baseline - barHeight,
            ),
            size = Size(barWidth, barHeight),
            cornerRadius = CornerRadius(barWidth / 2, barWidth / 2),
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHorizontalBars(
    values: List<Double?>,
    color: Color,
) {
    val numbers = values.mapNotNull { it?.toFloat() }
    val maximum = max(numbers.maxOrNull() ?: 0f, 1f)
    val slot = size.height / values.size
    val barHeight = max(2.dp.toPx(), slot * 0.48f)
    values.forEachIndexed { index, raw ->
        val centerY = slot * index + slot / 2
        drawRoundRect(
            color = color.copy(alpha = 0.08f),
            topLeft = Offset(0f, centerY - barHeight / 2),
            size = Size(size.width, barHeight),
            cornerRadius = CornerRadius(barHeight / 2, barHeight / 2),
        )
        val value = raw?.toFloat() ?: return@forEachIndexed
        val ratio = (value / maximum).coerceIn(0f, 1f)
        drawRoundRect(
            color = color.copy(alpha = if (index == values.lastIndex) 1f else 0.48f),
            topLeft = Offset(0f, centerY - barHeight / 2),
            size = Size(max(size.width * ratio, barHeight), barHeight),
            cornerRadius = CornerRadius(barHeight / 2, barHeight / 2),
        )
    }
}

private fun healthTargetText(metric: HealthMetricVisual, target: Double): String {
    val current = metric.value ?: 0.0
    if (metric.key == "sleep") {
        val remaining = (target - current).roundToInt()
        return if (remaining <= 0) {
            "${target.roundToInt() / 60}h · 已达标"
        } else {
            "${target.roundToInt() / 60}h · 还差 $remaining 分"
        }
    }
    val percent = ((current / target) * 100).roundToInt().coerceAtLeast(0)
    val targetText = when (metric.key) {
        "steps" -> "目标 ${target.roundToInt()}"
        "standing" -> "${target.roundToInt()} 小时"
        else -> "目标 ${target.roundToInt()}"
    }
    return "$targetText · $percent%"
}

private fun healthMetricLabel(key: String): String = when (key) {
    "sleep" -> "睡眠时长"
    "deep_sleep" -> "深睡"
    "deep_ratio" -> "深睡占比"
    "awake" -> "夜醒"
    "wake_time" -> "醒来时间"
    "steps" -> "今日步数"
    "calories" -> "活动热量"
    "exercise_minutes" -> "运动时长"
    "activity_minutes" -> "中高强度活动"
    "standing" -> "有效站立"
    "sedentary" -> "最长久坐"
    "heart" -> "心率"
    "resting_heart" -> "静息心率"
    "stress" -> "压力指数"
    "oxygen" -> "血氧"
    else -> key
}

private fun healthMetricGlyph(key: String): String = when (key) {
    "sleep" -> "眠"
    "deep_sleep" -> "深"
    "deep_ratio" -> "深"
    "awake" -> "醒"
    "wake_time" -> "起"
    "steps" -> "步"
    "calories" -> "热"
    "exercise_minutes" -> "动"
    "activity_minutes" -> "动"
    "standing" -> "立"
    "sedentary" -> "坐"
    "heart" -> "心"
    "resting_heart" -> "静"
    "stress" -> "压"
    "oxygen" -> "氧"
    else -> "健"
}

private fun healthDisplayUnit(metric: HealthMetricVisual): String = when (metric.key) {
    "sleep",
    "deep_sleep",
    "awake",
    "wake_time",
    -> ""
    else -> metric.unit
}

private val HealthVisualJson = Json { ignoreUnknownKeys = true }
