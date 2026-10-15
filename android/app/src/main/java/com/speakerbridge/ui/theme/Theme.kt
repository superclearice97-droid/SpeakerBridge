package com.speakerbridge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.speakerbridge.R

/** Editorial dark palette: near-black, hairline grid, warm cream type, one hot red. */
object SB {
    val Ink = Color(0xFF121212)          // page
    val Panel = Color(0xFF171717)        // cells
    val Line = Color(0xFF2A2A2A)         // hairlines
    val LineSoft = Color(0xFF1F1F1F)     // floor grid
    val Cream = Color(0xFFF2EDE1)        // primary text
    val Muted = Color(0xFF8E8B84)        // secondary text
    val Dim = Color(0xFF5E5C58)          // captions
    val Red = Color(0xFFEF3A2D)          // accent / buttons
    val RedDeep = Color(0xFFB8261C)
    val Ready = Color(0xFFE9A23B)
    val Verified = Color(0xFF7BD389)
    val Group = Color(0xFF7E8CFF)

    /** Saturated poster blocks used for speaker tiles on the floor. */
    val Tiles = listOf(
        Color(0xFF2B3BF5), Color(0xFFFF2E9A), Color(0xFFC8F25A), Color(0xFFFF6A2B),
        Color(0xFFF2EDE1), Color(0xFF6B3BFF), Color(0xFF19C5A5), Color(0xFFFFD23F),
    )
}

val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)
val SerifFamily = FontFamily(
    Font(R.font.lora, FontWeight.Normal),
    Font(R.font.lora_italic, FontWeight.Normal, FontStyle.Italic),
)
val MonoFamily = FontFamily(Font(R.font.dejavu_mono, FontWeight.Normal))

/** Tall, condensed serif for headlines: Lora squeezed horizontally. */
private val Condensed = TextGeometricTransform(scaleX = 0.74f)

object SBType {
    val Display = TextStyle(fontFamily = SerifFamily, fontSize = 50.sp, lineHeight = 46.sp,
        letterSpacing = (-0.01).em, textGeometricTransform = Condensed, color = SB.Cream)
    val Headline = TextStyle(fontFamily = SerifFamily, fontSize = 30.sp, lineHeight = 30.sp,
        textGeometricTransform = Condensed, color = SB.Cream)
    val Tagline = TextStyle(fontFamily = SerifFamily, fontSize = 17.sp, lineHeight = 20.sp,
        textGeometricTransform = Condensed, color = SB.Cream)
    val Body = TextStyle(fontFamily = InterFamily, fontSize = 14.sp, lineHeight = 20.sp, color = SB.Cream)
    val BodySmall = TextStyle(fontFamily = InterFamily, fontSize = 12.sp, lineHeight = 17.sp, color = SB.Muted)
    val Row = TextStyle(fontFamily = InterFamily, fontSize = 15.sp, lineHeight = 20.sp, color = SB.Cream)
    val Mono = TextStyle(fontFamily = MonoFamily, fontSize = 10.sp, lineHeight = 14.sp,
        letterSpacing = 0.08.em, color = SB.Muted)
    val MonoLabel = Mono.copy(color = SB.Red)
    val Button = TextStyle(fontFamily = SerifFamily, fontSize = 20.sp, textGeometricTransform = Condensed,
        color = SB.Cream)
    val WordSans = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp,
        letterSpacing = (-0.03).em, color = SB.Cream)
    val WordSerif = TextStyle(fontFamily = SerifFamily, fontStyle = FontStyle.Italic, fontSize = 24.sp,
        color = SB.Cream)
}

@Composable
fun SpeakerBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = SB.Red, onPrimary = SB.Cream,
            background = SB.Ink, onBackground = SB.Cream,
            surface = SB.Panel, onSurface = SB.Cream,
            surfaceVariant = SB.Panel, onSurfaceVariant = SB.Muted,
            outline = SB.Line,
        ),
        content = content,
    )
}
