package dev.southstar21.volum.ui.components

import androidx.compose.ui.draw.clip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.southstar21.volum.ui.theme.Bold
import dev.southstar21.volum.ui.theme.ExtraBold
import dev.southstar21.volum.ui.theme.Semibold
import dev.southstar21.volum.ui.theme.T
import dev.southstar21.volum.ui.theme.Volum
import dev.southstar21.volum.ui.theme.riseIn
import dev.southstar21.volum.ui.theme.textStyle


@Composable
fun Txt(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = T.Body,
    weight: FontWeight = FontWeight.Normal,
    color: Color = Volum.colors.ink,
    tracking: TextUnit = 0.em,
    align: TextAlign = TextAlign.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    tabular: Boolean = false,
    lineHeight: TextUnit = TextUnit.Unspecified,
) {
    BasicText(
        text,
        modifier,
        style = textStyle(size, weight, color, tracking, align, lineHeight, tabular),
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

@Composable
fun Mute(text: String, modifier: Modifier = Modifier, size: TextUnit = T.Small, weight: FontWeight = FontWeight.Normal, maxLines: Int = Int.MAX_VALUE, align: TextAlign = TextAlign.Unspecified) =
    Txt(text, modifier, size, weight, Volum.colors.mute, maxLines = maxLines, align = align)

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) =
    Txt(text, modifier.riseIn(14f), size = T.Title, weight = ExtraBold, tracking = T.TitleTracking)

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Txt(text, Modifier.weight(1f), size = T.Subhead, weight = Bold)
        trailing?.let { Mute(it, weight = Semibold) }
    }
}

/** Big number with a small unit: "62.5 kg". */
@Composable
fun NumberUnit(value: String, unit: String, modifier: Modifier = Modifier, size: TextUnit = T.Heading, color: Color = Volum.colors.ink) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Txt(value, size = size, weight = ExtraBold, tracking = (-0.02).em, tabular = true, color = color)
        Spacer(Modifier.width(3.dp))
        Txt(unit, Modifier.padding(bottom = 2.dp), size = T.Caption, weight = Semibold, color = Volum.colors.mute)
    }
}

/** An error message under a form: red, and announced by screen readers when it appears. */
@Composable
fun ErrorText(text: String, modifier: Modifier = Modifier, size: TextUnit = T.Small, weight: FontWeight = FontWeight.Normal) {
    Txt(text, modifier.semantics { liveRegion = LiveRegionMode.Polite }, size = size, weight = weight, color = Volum.colors.danger)
}

/** Small tag like PR. */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier) {
    val c = Volum.colors
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(c.soft).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Txt(text, size = T.Tiny, weight = ExtraBold, color = c.accent, tracking = 0.04.em)
    }
}
