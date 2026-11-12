package dev.southstar21.volum.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Stroke icons drawn from SVG path data (24 × 24, round caps), as in the mockups. */
object Icons {
    private fun stroke(name: String, vararg paths: String, width: Float = 2f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = PathParser().parsePathString(d).toNodes(),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = width,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    private fun fill(name: String, vararg paths: String, evenOdd: Boolean = false): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = PathParser().parsePathString(d).toNodes(),
                    pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
                    fill = SolidColor(Color.Black),
                )
            }
        }.build()

    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} $cy a$r $r 0 1 0 ${2 * r} 0 a$r $r 0 1 0 ${-2 * r} 0"
    private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r} $y h${w - 2 * r} a$r $r 0 0 1 $r $r v${h - 2 * r} a$r $r 0 0 1 ${-r} $r h${-(w - 2 * r)} a$r $r 0 0 1 ${-r} ${-r} v${-(h - 2 * r)} a$r $r 0 0 1 $r ${-r} z"

    val ChevronLeft = stroke("chevronLeft", "M15 18l-6-6 6-6")
    val ChevronRight = stroke("chevronRight", "M9 18l6-6-6-6")
    val ChevronDown = stroke("chevronDown", "M6 9l6 6 6-6")
    val Plus = stroke("plus", "M12 5v14M5 12h14")
    val Minus = stroke("minus", "M5 12h14")
    val Check = stroke("check", "M20 6L9 17l-5-5", width = 2.6f)
    val X = stroke("x", "M18 6L6 18M6 6l12 12")
    val More = fill("more", circle(5f, 12f, 1.6f), circle(12f, 12f, 1.6f), circle(19f, 12f, 1.6f))
    val Search = stroke("search", circle(11f, 11f, 7f), "M21 21l-4.3-4.3")
    val Dumbbell = stroke("dumbbell", "M6.5 6.5l11 11M21 21l-1-1M3 3l1 1M18 22l4-4M2 6l4-4M3 10l7-7M14 21l7-7")
    val Sort = stroke("sort", "M7 4v16M3 16l4 4 4-4M17 20V4M13 8l4-4 4 4")
    // The tab bar's icons, drawn after the filled SF Symbols the iOS bar shows on every tab
    // (house.fill, calendar, dumbbell.fill, person.crop.circle.fill).
    private fun glyph(name: String, fills: List<String>, strokes: List<String> = emptyList(), width: Float = 2f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            fills.forEach { d -> addPath(pathData = PathParser().parsePathString(d).toNodes(), pathFillType = PathFillType.EvenOdd, fill = SolidColor(Color.Black)) }
            strokes.forEach { d ->
                addPath(
                    pathData = PathParser().parsePathString(d).toNodes(),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = width,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val TabHome = glyph(
        "tabHome",
        fills = listOf(
            // The body, with the door cut out of its foot, under the roof line.
            "M4.9 12.4L12 6.5l7.1 5.9V19.8a1.7 1.7 0 0 1-1.7 1.7H14.2v-5.2a.8 .8 0 0 0-.8-.8h-2.8a.8 .8 0 0 0-.8 .8v5.2H6.6a1.7 1.7 0 0 1-1.7-1.7z",
            // The chimney.
            "M15.9 4.6h2.2a.4 .4 0 0 1 .4 .4v4.1L15.5 6.6V5a.4 .4 0 0 1 .4-.4z",
        ),
        strokes = listOf("M2.6 11.6L12 3.8l9.4 7.8"),
        width = 2.1f,
    )
    val TabHistory = glyph(
        "tabHistory",
        fills = buildList {
            // The outline with a solid header band, then the dates as dots.
            add(rect(2.8f, 3.8f, 18.4f, 17.2f, 3.6f) + rect(4.6f, 9.4f, 14.8f, 9.8f, 1.9f))
            val xs = listOf(7.4f, 10.5f, 13.5f, 16.6f)
            listOf(12.1f, 14.7f, 17.3f).forEach { y -> xs.forEach { x -> add(circle(x, y, 1.05f)) } }
        },
    )
    val TabLifts = glyph(
        "tabLifts",
        fills = listOf(
            rect(0.6f, 8f, 3.2f, 8f, 1.4f),
            rect(4.5f, 4.6f, 4.2f, 14.8f, 1.9f),
            rect(9.6f, 10.4f, 4.8f, 3.2f, 1.3f),
            rect(15.3f, 4.6f, 4.2f, 14.8f, 1.9f),
            rect(20.2f, 8f, 3.2f, 8f, 1.4f),
        ),
    )
    val TabProfile = fill(
        "tabProfile",
        circle(12f, 12f, 10.2f) + circle(12f, 9.4f, 3.5f) + "M5.5 17.5A6.6 5.3 0 0 1 18.5 17.5A8.3 8.3 0 0 1 5.5 17.5z",
        evenOdd = true,
    )
    val Users = stroke("users", circle(9f, 8f, 4f), "M2 21a7 7 0 0 1 14 0M16 3.5a4 4 0 0 1 0 8M22 21a7 7 0 0 0-4-6.3")
    val Trash = stroke("trash", "M3 6h18M8 6V4h8v2M6 6l1 14h10l1-14")
    val Pencil = stroke("pencil", "M17 3a2.8 2.8 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5z")
    val Skip = stroke("skip", "M5 4l10 8-10 8V4zM19 5v14")
    val Timer = stroke("timer", circle(12f, 13f, 8f), "M12 9v4l2 2M9 2h6")
    val CloudCheck = stroke("cloudCheck", "M17.5 19a4.5 4.5 0 1 0-1.4-8.8A7 7 0 1 0 6 18.6", "M9 15l2 2 4-4")
    val CloudOff = stroke("cloudOff", "M2 2l20 20", "M5.8 8.6A6 6 0 0 0 7 20h10", "M21.5 16.5A4.5 4.5 0 0 0 17.5 10h-1.8A7 7 0 0 0 9.6 5.3")
    val CloudSync = stroke("cloudSync", "M17.5 19a4.5 4.5 0 1 0-1.4-8.8A7 7 0 1 0 6 18.6", "M12 13v5M9.5 15.5L12 13l2.5 2.5")
    val Grip = fill("grip", circle(9f, 6f, 1.4f), circle(15f, 6f, 1.4f), circle(9f, 12f, 1.4f), circle(15f, 12f, 1.4f), circle(9f, 18f, 1.4f), circle(15f, 18f, 1.4f))
    val Lock = stroke("lock", rect(4f, 11f, 16f, 10f, 2f), "M8 11V7a4 4 0 0 1 8 0v4")
    val Stop = fill("stop", rect(6f, 6f, 12f, 12f, 2.6f))
    val Play = fill("play", "M7 4v16l13-8z")
    val Swap = stroke("swap", "M7 7h11l-3-3M17 17H6l3 3")
    val Refresh = stroke("refresh", "M21 12a9 9 0 1 1-3-6.7L21 8", "M21 3v5h-5")
    val ArrowRight = stroke("arrowRight", "M5 12h14M13 6l6 6-6 6")
    val Copy = stroke("copy", rect(8f, 8f, 13f, 13f, 2f), "M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3")
}

@Composable
fun Icon(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 20.dp, contentDescription: String? = null) {
    Image(icon, contentDescription, modifier.size(size), colorFilter = ColorFilter.tint(tint))
}
