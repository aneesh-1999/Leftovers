package com.leftovers.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leftovers.app.data.investment.MarketPriceHistory
import com.leftovers.app.data.investment.MarketPriceSnapshot
import com.leftovers.app.ui.theme.LocalAppColors
import com.leftovers.app.util.Money
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

enum class ChartTimeframe(val label: String, val days: Long) {
    D1("1D", 1L),
    W1("1W", 7L),
    M1("1M", 30L),
    M3("3M", 90L),
    M6("6M", 180L),
    Y1("1Y", 365L),
    ALL("ALL", 3650L),
}

data class PricePoint(
    val timestamp: Long,
    val price: Double,
    val dateLabel: String,
)

/**
 * Beautiful, interactive financial price chart with:
 * - Timeframe filtering (1D, 1W, 1M, 3M, 6M, 1Y, ALL)
 * - Cubic spline line with gradient fill below the curve
 * - Touch scrubber tracking the finger with floating tooltip badge
 * - High / Low range reference guidelines
 * - Automatic trend color (positive green vs negative red)
 */
@Composable
fun MarketPriceChart(
    snapshot: MarketPriceSnapshot,
    history: List<MarketPriceHistory>,
    currency: String = "NPR",
    modifier: Modifier = Modifier,
) {
    val c = LocalAppColors.current
    val money = remember(currency) { Money(currency) }

    var selectedTimeframe by remember { mutableStateOf(ChartTimeframe.D1) }
    var scrubbedIndex by remember { mutableStateOf<Int?>(null) }

    // Build raw point list according to selected timeframe
    val points = remember(snapshot, history, selectedTimeframe) {
        buildPointsForTimeframe(snapshot, history, selectedTimeframe)
    }

    val isPositiveTrend = remember(points) {
        if (points.size >= 2) {
            points.last().price >= points.first().price
        } else {
            (snapshot.percentChange ?: 0.0) >= 0.0
        }
    }

    val trendColor by animateColorAsState(
        if (isPositiveTrend) c.positive else c.negative,
        label = "trendColor",
    )

    // Current displayed point (scrubbed or latest)
    val currentPoint = scrubbedIndex?.let { points.getOrNull(it) } ?: points.lastOrNull()
    val displayPrice = currentPoint?.price ?: snapshot.price
    val baselinePrice = points.firstOrNull()?.price ?: snapshot.previousClose ?: snapshot.price
    val periodDiff = displayPrice - baselinePrice
    val periodDiffPercent = if (baselinePrice > 0.0) (periodDiff / baselinePrice) * 100.0 else 0.0
    val isPeriodPositive = periodDiff >= 0.0

    // Animation progress when timeframe changes
    val animProgress = remember(selectedTimeframe, points) { Animatable(0f) }
    LaunchedEffect(selectedTimeframe, points) {
        animProgress.snapTo(0f)
        animProgress.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
    }

    Column(modifier.fillMaxWidth()) {
        // Price Header with Scrubber details
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Text(
                    text = "${money.symbol} ${String.format("%,.2f", displayPrice)}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = c.textPrimary,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${if (isPeriodPositive) "+" else ""}${money.symbol} ${String.format("%,.2f", periodDiff)} (${if (isPeriodPositive) "+" else ""}${String.format("%.2f", periodDiffPercent)}%)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isPeriodPositive) c.positive else c.negative,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = currentPoint?.dateLabel ?: "Today",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.textTertiary,
                    )
                }
            }

            if (scrubbedIndex != null) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.glassStrong)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text("Scrubbing", style = MaterialTheme.typography.labelSmall, color = c.accent)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Canvas Chart
        Box(
            Modifier
                .fillMaxWidth()
                .height(180.dp),
        ) {
            if (points.size <= 1) {
                // If only 1 price snapshot is available, display a clear daily range view
                SingleDayRangePreview(snapshot = snapshot, currency = currency)
            } else {
                InteractiveCurveChart(
                    points = points,
                    trendColor = trendColor,
                    progress = animProgress.value,
                    scrubbedIndex = scrubbedIndex,
                    onScrub = { scrubbedIndex = it },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Timeframe selector pills
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ChartTimeframe.values().forEach { tf ->
                val isSelected = selectedTimeframe == tf
                Glass(
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)),
                    tint = if (isSelected) trendColor.copy(alpha = 0.22f) else null,
                    onClick = {
                        scrubbedIndex = null
                        selectedTimeframe = tf
                    },
                ) {
                    Text(
                        text = tf.label,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) trendColor else c.textSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun InteractiveCurveChart(
    points: List<PricePoint>,
    trendColor: Color,
    progress: Float,
    scrubbedIndex: Int?,
    onScrub: (Int?) -> Unit,
) {
    val c = LocalAppColors.current
    val measurer = rememberTextMeasurer()
    val axisTextStyle = MaterialTheme.typography.labelSmall.copy(color = c.textTertiary, fontSize = 9.sp)

    val minPrice = points.minOf { it.price }
    val maxPrice = points.maxOf { it.price }
    val priceRange = (maxPrice - minPrice).coerceAtLeast(1.0)

    val touchModifier = Modifier.pointerInput(points) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val initialX = down.position.x
            val stepW = size.width / (points.size - 1).coerceAtLeast(1)
            val idx = (initialX / stepW).toInt().coerceIn(0, points.size - 1)
            onScrub(idx)

            var moved = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: break
                if (!change.pressed) break
                val delta = change.position - change.previousPosition
                if (abs(delta.x) > abs(delta.y)) change.consume()
                val newIdx = (change.position.x / stepW).toInt().coerceIn(0, points.size - 1)
                onScrub(newIdx)
                moved = true
            }
            onScrub(null)
        }
    }

    Canvas(
        Modifier
            .fillMaxSize()
            .then(touchModifier),
    ) {
        val w = size.width
        val h = size.height
        val padTop = 14.dp.toPx()
        val padBottom = 16.dp.toPx()
        val graphH = h - padTop - padBottom
        val stepX = w / (points.size - 1).coerceAtLeast(1)

        // Coordinates calculator
        fun pointOffset(index: Int): Offset {
            val p = points[index]
            val normY = (p.price - minPrice) / priceRange
            val x = index * stepX
            val y = padTop + graphH * (1f - (normY.toFloat() * progress))
            return Offset(x, y)
        }

        // 1. Draw High / Low reference dashed lines
        val dashedEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
        drawLine(
            color = c.borderTop.copy(alpha = 0.5f),
            start = Offset(0f, padTop),
            end = Offset(w, padTop),
            strokeWidth = 1.dp.toPx(),
            pathEffect = dashedEffect,
        )
        drawLine(
            color = c.borderTop.copy(alpha = 0.5f),
            start = Offset(0f, padTop + graphH),
            end = Offset(w, padTop + graphH),
            strokeWidth = 1.dp.toPx(),
            pathEffect = dashedEffect,
        )

        // High & Low text labels on right
        val highLayout = measurer.measure(String.format("%.1f", maxPrice), axisTextStyle)
        val lowLayout = measurer.measure(String.format("%.1f", minPrice), axisTextStyle)
        drawText(highLayout, topLeft = Offset(w - highLayout.size.width - 4.dp.toPx(), padTop - 12.dp.toPx()))
        drawText(lowLayout, topLeft = Offset(w - lowLayout.size.width - 4.dp.toPx(), padTop + graphH + 2.dp.toPx()))

        // 2. Build Cubic Spline Path
        val path = Path()
        val fillPath = Path()

        val firstPt = pointOffset(0)
        path.moveTo(firstPt.x, firstPt.y)
        fillPath.moveTo(firstPt.x, h)
        fillPath.lineTo(firstPt.x, firstPt.y)

        for (i in 1 until points.size) {
            val prev = pointOffset(i - 1)
            val curr = pointOffset(i)
            val cX1 = prev.x + (curr.x - prev.x) / 2f
            val cY1 = prev.y
            val cX2 = prev.x + (curr.x - prev.x) / 2f
            val cY2 = curr.y

            path.cubicTo(cX1, cY1, cX2, cY2, curr.x, curr.y)
            fillPath.cubicTo(cX1, cY1, cX2, cY2, curr.x, curr.y)
        }

        val lastPt = pointOffset(points.size - 1)
        fillPath.lineTo(lastPt.x, h)
        fillPath.close()

        // 3. Draw Gradient Fill below curve
        val fillBrush = Brush.verticalGradient(
            colors = listOf(
                trendColor.copy(alpha = 0.28f * progress),
                trendColor.copy(alpha = 0.05f * progress),
                Color.Transparent,
            ),
            startY = padTop,
            endY = h,
        )
        drawPath(fillPath, brush = fillBrush)

        // 4. Draw Smooth Line
        drawPath(
            path = path,
            color = trendColor,
            style = Stroke(
                width = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
            ),
        )

        // 5. Draw Touch Scrubber Cursor & Dot
        if (scrubbedIndex != null && scrubbedIndex in points.indices) {
            val activePt = pointOffset(scrubbedIndex)

            // Vertical hairline
            drawLine(
                color = c.textTertiary.copy(alpha = 0.7f),
                start = Offset(activePt.x, 0f),
                end = Offset(activePt.x, h),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f),
            )

            // Outer glowing dot
            drawCircle(
                color = trendColor.copy(alpha = 0.25f),
                radius = 8.dp.toPx(),
                center = activePt,
            )
            // Center solid dot
            drawCircle(
                color = trendColor,
                radius = 4.dp.toPx(),
                center = activePt,
            )
            drawCircle(
                color = Color.White,
                radius = 2.dp.toPx(),
                center = activePt,
            )
        }
    }
}

@Composable
private fun SingleDayRangePreview(
    snapshot: MarketPriceSnapshot,
    currency: String,
) {
    val c = LocalAppColors.current
    val prevClose = snapshot.previousClose ?: snapshot.price
    val high = snapshot.high ?: snapshot.price
    val low = snapshot.low ?: minOf(snapshot.price, prevClose)
    val ltp = snapshot.price

    Glass(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp)),
        strong = true,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Today's Trading Range", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                Text("Live Session", style = MaterialTheme.typography.labelSmall, color = c.accent)
            }

            // Day range visual horizontal bar
            val totalSpan = (high - low).coerceAtLeast(0.01)
            val markerFraction = ((ltp - low) / totalSpan).coerceIn(0.0, 1.0).toFloat()

            Column(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(c.glassStrong),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction = markerFraction)
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(Brush.horizontalGradient(listOf(c.warning, c.positive))),
                    )
                }

                Spacer(Modifier.height(6.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Day Low: Rs $low", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                    Text("LTP: Rs $ltp", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = c.textPrimary)
                    Text("Day High: Rs $high", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Previous Close: Rs $prevClose", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                snapshot.volume?.let {
                    Text("Volume: $it shares", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
            }
        }
    }
}

/**
 * Builds chronological price points for the selected timeframe.
 * If actual historical points exist in database, filters and uses them.
 * If 1D is selected, uses today's anchor bounds: Prev Close -> Open/Low -> High -> Current LTP.
 */
private fun buildPointsForTimeframe(
    snapshot: MarketPriceSnapshot,
    history: List<MarketPriceHistory>,
    timeframe: ChartTimeframe,
): List<PricePoint> {
    val now = System.currentTimeMillis()
    val cutoff = if (timeframe == ChartTimeframe.ALL) 0L else now - (timeframe.days * 86_400_000L)

    // Filter relevant historical points
    val filteredHistory = history
        .filter { it.timestamp >= cutoff }
        .sortedBy { it.timestamp }

    if (filteredHistory.size >= 2) {
        val formatter = DateTimeFormatter.ofPattern("MMM dd")
        val pts = filteredHistory.map {
            val date = runCatching { LocalDate.parse(it.tradingDate) }.getOrNull()
            val label = date?.format(formatter) ?: it.tradingDate
            PricePoint(it.timestamp, it.price, label)
        }.toMutableList()

        // Append current snapshot if newer
        if (pts.last().price != snapshot.price) {
            pts.add(PricePoint(snapshot.timestamp, snapshot.price, "Now"))
        }
        return pts
    }

    // If 1D view or only current session exists, synthesize today's session trajectory
    if (timeframe == ChartTimeframe.D1) {
        val prevClose = snapshot.previousClose ?: snapshot.price
        val low = snapshot.low ?: minOf(snapshot.price, prevClose)
        val high = snapshot.high ?: maxOf(snapshot.price, prevClose)
        val ltp = snapshot.price

        val t0 = snapshot.timestamp - 4 * 3600 * 1000L
        val t1 = snapshot.timestamp - 3 * 3600 * 1000L
        val t2 = snapshot.timestamp - 2 * 3600 * 1000L
        val t3 = snapshot.timestamp - 1 * 3600 * 1000L
        val t4 = snapshot.timestamp

        return listOf(
            PricePoint(t0, prevClose, "11:00 AM (Prev)"),
            PricePoint(t1, if (snapshot.change != null && snapshot.change >= 0) low else high, "12:00 PM"),
            PricePoint(t2, (high + low) / 2.0, "01:00 PM"),
            PricePoint(t3, if (snapshot.change != null && snapshot.change >= 0) high else low, "02:00 PM"),
            PricePoint(t4, ltp, "03:00 PM (LTP)"),
        )
    }

    // Default with at least start & end
    val prev = snapshot.previousClose ?: snapshot.price
    return listOf(
        PricePoint(snapshot.timestamp - 86400000L, prev, "Previous Close"),
        PricePoint(snapshot.timestamp, snapshot.price, "Latest LTP"),
    )
}
