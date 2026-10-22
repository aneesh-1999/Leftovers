package com.leftovers.app.ui.screens.creditcard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leftovers.app.data.creditcard.CardNetwork
import com.leftovers.app.data.creditcard.CreditCard
import com.leftovers.app.data.creditcard.CreditCardStatus

/**
 * Realistic luxury physical credit card component.
 * Renders realistic ISO aspect ratio card face, metallic jewel gradient,
 * EMV chip, contactless wave glyph, stylized network branding, tracked digits, and status pill.
 */
@Composable
fun CreditCardVisual(
    card: CreditCard,
    status: CreditCardStatus? = null,
    modifier: Modifier = Modifier,
) {
    val cardBaseColor = Color(card.color)
    val cardShape = RoundedCornerShape(22.dp)

    // Deep metallic / jewel glass gradient
    val backgroundBrush = Brush.linearGradient(
        colors = listOf(
            cardBaseColor.copy(alpha = 0.90f),
            Color(0xFF1B1E29).copy(alpha = 0.94f),
            Color(0xFF0C0E14),
        ),
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
    )

    // Specular diagonal sheen reflection
    val sheenBrush = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.18f),
            Color.Transparent,
            Color.White.copy(alpha = 0.04f),
        ),
        start = Offset(0f, 0f),
        end = Offset(500f, 500f),
    )

    // Chamfered polished edge highlight
    val borderBrush = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.40f),
            cardBaseColor.copy(alpha = 0.30f),
            Color.White.copy(alpha = 0.08f),
        ),
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1.586f) // Standard physical credit card aspect ratio
            .clip(cardShape)
            .background(backgroundBrush)
            .background(sheenBrush)
            .border(1.dp, borderBrush, cardShape),
    ) {
        // Ambient background subtle glow
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        cardBaseColor.copy(alpha = 0.25f),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.85f, size.height * 0.2f),
                    radius = size.width * 0.5f,
                ),
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // --- TOP ROW: Bank Issuer & Network Branding ---
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Bank Issuer Name
                Text(
                    text = card.bankIssuer.ifBlank { "CREDIT CARD" }.uppercase(),
                    style = TextStyle(
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.90f),
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )

                Spacer(Modifier.width(8.dp))

                // Network Brand Badge
                CardNetworkBadge(network = card.network)
            }

            // --- MIDDLE ROW: EMV Smart Chip + Contactless Glyph ---
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EmvChipGraphic()
                ContactlessWaveGlyph()
            }

            // --- CARD NUMBER: Embossed Tracked Digits ---
            val lastFour = card.lastFourDigits.ifBlank { "••••" }
            Text(
                text = "••••  ••••  ••••  $lastFour",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    letterSpacing = 2.5.sp,
                    color = Color.White.copy(alpha = 0.95f),
                ),
                maxLines = 1,
            )

            // --- BOTTOM ROW: Cardholder Nickname & Cycle / Status ---
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                // Cardholder Nickname
                Column(Modifier.weight(1f, fill = false)) {
                    Text(
                        text = "CARDHOLDER",
                        style = TextStyle(
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.2.sp,
                            color = Color.White.copy(alpha = 0.55f),
                        ),
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = card.nickname.ifBlank { "MY CARD" }.uppercase(),
                        style = TextStyle(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = Color.White,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Billing Cycle Day
                if (card.billingCycleClosingDay in 1..31) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "CLOSES",
                            style = TextStyle(
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.sp,
                                color = Color.White.copy(alpha = 0.55f),
                            ),
                        )
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = "DAY ${card.billingCycleClosingDay}",
                            style = TextStyle(
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.85f),
                            ),
                        )
                    }
                }

                // Status Badge
                if (status != null) {
                    CardStatusBadge(status = status)
                }
            }
        }
    }
}

/**
 * Realistic Gold EMV Smart Chip graphic
 */
@Composable
private fun EmvChipGraphic(modifier: Modifier = Modifier) {
    val chipShape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .size(width = 38.dp, height = 28.dp)
            .clip(chipShape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFFF7DC6F),
                        Color(0xFFD4AC0D),
                        Color(0xFFB7950B),
                        Color(0xFFF1C40F),
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(80f, 60f),
                ),
            )
            .border(1.dp, Color(0x66FFFFFF), chipShape),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val lineCol = Color(0x66000000)
            val stroke = Stroke(width = 1.dp.toPx())

            // Horizontal split line
            drawLine(lineCol, Offset(0f, h * 0.5f), Offset(w, h * 0.5f), strokeWidth = 1.dp.toPx())
            // Vertical split lines
            drawLine(lineCol, Offset(w * 0.34f, 0f), Offset(w * 0.34f, h), strokeWidth = 1.dp.toPx())
            drawLine(lineCol, Offset(w * 0.66f, 0f), Offset(w * 0.66f, h), strokeWidth = 1.dp.toPx())

            // Center circuit contact
            drawRoundRect(
                color = Color(0x22000000),
                topLeft = Offset(w * 0.34f, h * 0.28f),
                size = Size(w * 0.32f, h * 0.44f),
                cornerRadius = CornerRadius(2.dp.toPx()),
                style = stroke,
            )
        }
    }
}

/**
 * Contactless Radio-Frequency payment wave icon
 */
@Composable
private fun ContactlessWaveGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier.size(20.dp)) {
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
        val color = Color.White.copy(alpha = 0.70f)
        val cx = size.width * 0.15f
        val cy = size.height * 0.5f

        // 3 concentric wave arcs
        drawArc(
            color = color,
            startAngle = -42f,
            sweepAngle = 84f,
            useCenter = false,
            topLeft = Offset(cx - 3.dp.toPx(), cy - 5.dp.toPx()),
            size = Size(10.dp.toPx(), 10.dp.toPx()),
            style = stroke,
        )
        drawArc(
            color = color,
            startAngle = -42f,
            sweepAngle = 84f,
            useCenter = false,
            topLeft = Offset(cx - 6.dp.toPx(), cy - 9.dp.toPx()),
            size = Size(18.dp.toPx(), 18.dp.toPx()),
            style = stroke,
        )
        drawArc(
            color = color,
            startAngle = -42f,
            sweepAngle = 84f,
            useCenter = false,
            topLeft = Offset(cx - 9.dp.toPx(), cy - 13.dp.toPx()),
            size = Size(26.dp.toPx(), 26.dp.toPx()),
            style = stroke,
        )
    }
}

/**
 * Styled Card Network brand badge
 */
@Composable
private fun CardNetworkBadge(network: String) {
    when (network.trim().uppercase()) {
        CardNetwork.VISA.label.uppercase() -> {
            Text(
                text = "VISA",
                style = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Black,
                    fontStyle = FontStyle.Italic,
                    fontSize = 17.sp,
                    letterSpacing = 1.5.sp,
                    color = Color.White,
                ),
            )
        }
        CardNetwork.MASTERCARD.label.uppercase() -> {
            // Overlapping red & orange-yellow circles
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEB001B).copy(alpha = 0.90f)),
                )
                Box(
                    Modifier
                        .offset(x = (-7).dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFF79E1B).copy(alpha = 0.90f)),
                )
            }
        }
        CardNetwork.AMEX.label.uppercase() -> {
            Box(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF007BC1).copy(alpha = 0.85f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "AMEX",
                    style = TextStyle(
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                        color = Color.White,
                    ),
                )
            }
        }
        CardNetwork.FONEPAY.label.uppercase(), "FONEPAY" -> {
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFD32F2F).copy(alpha = 0.95f))
                    .border(0.6.dp, Color.White.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 2.5.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "fone",
                        style = TextStyle(
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.5.sp,
                            letterSpacing = 0.3.sp,
                            color = Color.White,
                        ),
                    )
                    Text(
                        text = "pay",
                        style = TextStyle(
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.Black,
                            fontSize = 11.5.sp,
                            letterSpacing = 0.3.sp,
                            color = Color(0xFFFFD54F),
                        ),
                    )
                }
            }
        }
        else -> {
            if (network.isNotBlank()) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                        .border(1.dp, Color.White.copy(alpha = 0.20f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = network.uppercase(),
                        style = TextStyle(
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            letterSpacing = 1.sp,
                            color = Color.White.copy(alpha = 0.90f),
                        ),
                    )
                }
            }
        }
    }
}

/**
 * Clean floating card status badge
 */
@Composable
private fun CardStatusBadge(status: CreditCardStatus) {
    val (dotColor, text) = when (status) {
        CreditCardStatus.PAID -> Color(0xFF2ECC71) to "PAID"
        CreditCardStatus.OVERDUE -> Color(0xFFE74C3C) to "OVERDUE"
        CreditCardStatus.PAYMENT_DUE_SOON -> Color(0xFFFFA000) to "DUE SOON"
        CreditCardStatus.PAYMENT_UPCOMING -> Color(0xFF3498DB) to "UPCOMING"
        CreditCardStatus.NO_STATEMENT -> Color.White.copy(alpha = 0.5f) to "NO DUE"
    }

    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .border(1.dp, dotColor.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = text,
                style = TextStyle(
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.8.sp,
                    color = Color.White,
                ),
            )
        }
    }
}
