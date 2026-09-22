package com.mcsoftware.paneldash

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun parseColorOrNull(s: String?): Color? {
    if (s.isNullOrBlank()) return null
    return try {
        val h = s.trim().removePrefix("#")
        val v = h.toLong(16)
        when (h.length) {
            6 -> Color(0xFF000000L or v)
            8 -> Color(v)
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

fun parseColor(s: String?, fallback: Long): Color = parseColorOrNull(s) ?: Color(fallback)

val ThemeCfg.bgColor: Color get() = parseColor(background, 0xFF000000)
val ThemeCfg.surfaceColor: Color get() = parseColor(surface, 0xFF161B21)
val ThemeCfg.accentColor: Color get() = parseColor(accent, 0xFF3D8BFD)
val ThemeCfg.textColor: Color get() = parseColor(text, 0xFFEAF0F6)

/** Per-component tint: node `color:` if set and valid, else the theme accent. */
fun tileTint(node: NodeCfg, theme: ThemeCfg): Color = parseColorOrNull(node.color) ?: theme.accentColor

/**
 * Chrome colors derived from the theme's polarity (light or dark background), so the
 * same tile design works in both modes: "off" tiles are near-black on a dark theme
 * and light-gray/white on a light theme.
 */
data class Palette(
    val offFill: Color,
    val offBorder: Color,
    val offLabel: Color,
    val offBig: Color,
    val navBg: Color,
    val navKey: Color,
    val navText: Color,
    val track: Color,
)

fun isLightTheme(theme: ThemeCfg): Boolean = theme.bgColor.luminance() > 0.5f

fun paletteFor(theme: ThemeCfg): Palette =
    if (isLightTheme(theme)) Palette(
        offFill = theme.surfaceColor,
        offBorder = lerp(theme.surfaceColor, Color.Black, 0.13f),
        offLabel = lerp(theme.textColor, theme.bgColor, 0.42f),
        offBig = lerp(theme.textColor, theme.bgColor, 0.30f),
        navBg = lerp(theme.bgColor, Color.Black, 0.05f),
        navKey = lerp(theme.surfaceColor, Color.White, 0.25f),
        navText = lerp(theme.textColor, theme.bgColor, 0.42f),
        track = lerp(theme.surfaceColor, Color.Black, 0.12f),
    ) else Palette(
        // Dark theme contrast: readable at a glance. Off-tile text holds ~8-9:1
        // against the tile fill (was ~4:1 label / ~2.3:1 big - the big state
        // line was nearly invisible). Tiles sit a touch above the page black so
        // the shape reads without leaning on the border.
        offFill = lerp(theme.surfaceColor, Color.Black, 0.50f),
        offBorder = lerp(theme.surfaceColor, Color.White, 0.14f),
        offLabel = lerp(theme.textColor, theme.bgColor, 0.30f),
        offBig = lerp(theme.textColor, theme.bgColor, 0.26f),
        navBg = lerp(theme.bgColor, Color.Black, 0.35f),
        navKey = lerp(theme.surfaceColor, Color.Black, 0.55f),
        navText = lerp(theme.textColor, theme.bgColor, 0.42f),
        track = lerp(theme.surfaceColor, Color.White, 0.06f),
    )

/** Warn color for tripped health checks (readable on both palettes). */
fun warnColor(theme: ThemeCfg): Color =
    if (isLightTheme(theme)) Color(0xFFD93A3F) else Color(0xFFE5484D)

/** Four slotted screw heads pinned near the screen corners: wall-mounted device vibe. */
fun DrawScope.drawPanelScrews(metalLight: Color, metalDark: Color) {
    val inset = 7.dp.toPx()
    val r = 4.dp.toPx()
    val corners = listOf(
        Offset(inset, inset),
        Offset(size.width - inset, inset),
        Offset(inset, size.height - inset),
        Offset(size.width - inset, size.height - inset),
    )
    corners.forEach { c ->
        drawCircle(color = metalLight, radius = r, center = c)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.30f), Color.Transparent),
                center = Offset(c.x - r * 0.35f, c.y - r * 0.35f),
                radius = r * 1.5f,
            ),
            radius = r, center = c,
        )
        drawCircle(color = metalDark, radius = r, center = c, style = Stroke(0.9.dp.toPx()))
        val d = r * 0.55f
        drawLine(
            metalDark,
            Offset(c.x - d, c.y - d),
            Offset(c.x + d, c.y + d),
            strokeWidth = 1.1.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/**
 * Status tile (flat wall-panel style): small uppercase label on top, one huge
 * state line. OFF = near-black tile with a thin border; ON = full saturated
 * fill in the tile's own color, dark text. Tapping the whole tile acts.
 */
@Composable
private fun StatusTile(
    modifier: Modifier,
    theme: ThemeCfg,
    lit: Boolean,
    tint: Color,
    label: String,
    big: String,
    onTap: () -> Unit,
) {
    val scale = theme.fontScale.coerceIn(0.5f, 3f)
    val corner = RoundedCornerShape(theme.radius.coerceIn(0, 40).dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val pal = paletteFor(theme)
    val fill = when {
        lit && pressed -> lerp(tint, Color.Black, 0.18f)
        lit -> tint
        pressed -> lerp(pal.offFill, Color.Black, 0.10f)
        else -> pal.offFill
    }
    val borderCol = pal.offBorder
    val labelCol = if (lit) lerp(tint, Color.Black, 0.70f) else pal.offLabel
    val bigCol = if (lit) lerp(tint, Color.Black, 0.88f) else pal.offBig
    val bigSp = when {
        big.length <= 3 -> 30f
        big.length <= 5 -> 25f
        big.length <= 8 -> 20f
        else -> 16f
    }

    Box(
        modifier
            .heightIn(min = 56.dp)
            .scale(if (pressed) 0.97f else 1f)
            .clip(corner)
            .background(fill)
            .then(if (lit) Modifier else Modifier.border(1.dp, borderCol, corner))
            .clickable(interactionSource = interaction, indication = null) { onTap() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                label.uppercase(),
                color = labelCol,
                fontSize = (12.5f * scale).sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                big,
                color = bigCol,
                fontSize = (bigSp * scale).sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun RenderNode(node: NodeCfg, theme: ThemeCfg, dash: Dashboard, modifier: Modifier = Modifier, inRow: Boolean = false) {
    val scope = rememberCoroutineScope()
    val scale = theme.fontScale.coerceIn(0.5f, 3f)
    val corner = RoundedCornerShape(theme.radius.coerceIn(0, 40).dp)

    when (node.type) {
        "vstack" -> Column(
            modifier
                .fillMaxWidth()
                .padding((node.padding ?: 0).dp),
            verticalArrangement = Arrangement.spacedBy((node.spacing ?: 8).dp),
        ) {
            node.children.forEach { child ->
                val m = child.weight?.let { Modifier.weight(it.coerceAtLeast(0.01f)) } ?: Modifier
                RenderNode(child, theme, dash, m)
            }
        }

        "hstack" -> Row(
            modifier
                .fillMaxWidth()
                .padding((node.padding ?: 0).dp),
            horizontalArrangement = Arrangement.spacedBy((node.spacing ?: 8).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            node.children.forEach { child ->
                val m = child.weight?.let { Modifier.weight(it.coerceAtLeast(0.01f)) } ?: Modifier
                RenderNode(child, theme, dash, m, inRow = true)
            }
        }

        "button" -> {
            val st = node.entity?.let { dash.states[it] }
            val subEntity = node.subEntity ?: node.entity
            val subSt = subEntity?.let { dash.states[it] }
            val subText = node.sub?.let { fmt ->
                fmt.replace("{state}", subSt?.state ?: "\u2013")
                    .replace("{friendly}", subSt?.friendly ?: subEntity ?: "")
            }
            // highlight:false = pure action tile (never fills). Script tiles with no
            // entity of their own light up from their sub_entity state instead.
            val highlightable = node.highlight != false
            val lit = highlightable && (
                if (node.entity != null) Actions.isOn(st?.state) else Actions.isOn(subSt?.state)
            )
            val big = when {
                node.entity != null && highlightable -> if (lit) "ON" else "OFF"
                subText != null -> subText.uppercase()
                else -> "TAP"
            }
            StatusTile(
                modifier = modifier,
                theme = theme,
                lit = lit,
                tint = tileTint(node, theme),
                label = node.text ?: st?.friendly ?: "Button",
                big = big,
            ) { dash.tap(scope, node) }
        }

        "camera" -> {
            // Tap target for the full-screen live peek; the tile itself just
            // shows a calm VIEW affordance (nothing to toggle).
            val st = node.entity?.let { dash.states[it] }
            StatusTile(
                modifier = modifier,
                theme = theme,
                lit = false,
                tint = tileTint(node, theme),
                label = node.text ?: st?.friendly ?: "Camera",
                big = "VIEW",
            ) { dash.tap(scope, node) }
        }

        "check" -> {
            // Quiet health row: calm (near-black, dim text) while the condition
            // holds; red dot + red value on a tinted row when it trips.
            val st = node.entity?.let { dash.states[it] }
            val raw = node.attribute?.let { a -> st?.attrs?.get(a) } ?: st?.state
            val num = when (raw) {
                is Number -> raw.toFloat()
                is String -> raw.trim().toFloatOrNull()
                else -> null
            }
            val stateStr = st?.state?.trim().orEmpty()
            val missing = stateStr.isEmpty() ||
                stateStr.equals("unavailable", true) || stateStr.equals("unknown", true)
            var ok = !missing
            if (!missing) {
                node.okAbove?.let { ok = ok && num != null && num > it }
                node.okBelow?.let { ok = ok && num != null && num < it }
                node.okIs?.let { spec ->
                    val allowed = spec.split(',').map { s -> s.trim().lowercase() }
                        .filter { s -> s.isNotEmpty() }
                    if (allowed.isNotEmpty()) ok = ok && stateStr.lowercase() in allowed
                }
            }
            val warn = warnColor(theme)
            val pal = paletteFor(theme)
            val dot = if (ok) lerp(pal.offFill, pal.offLabel, 0.75f) else warn
            val bg = if (ok) pal.offFill else lerp(pal.offFill, warn, 0.20f)
            val borderC = if (ok) pal.offBorder else warn.copy(alpha = 0.55f)
            val labelC = if (ok) pal.offLabel else lerp(theme.textColor, warn, 0.55f)
            val valueC = if (ok) pal.offBig else warn
            val valueText = when {
                missing -> "\u2014"
                num != null -> {
                    val vs = if (num % 1f == 0f) num.toInt().toString()
                             else "%.1f".format(java.util.Locale.US, num)
                    (node.format ?: "{value}").replace("{value}", vs).replace("{state}", stateStr)
                }
                else -> (node.format ?: "{state}").replace("{state}", stateStr)
                    .replace("{value}", stateStr)
            }
            Row(
                modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .clip(corner)
                    .background(bg)
                    .border(1.dp, borderC, corner)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
                Text(
                    node.text ?: st?.friendly ?: node.entity.orEmpty(),
                    color = labelC,
                    fontSize = (14f * scale).sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.4.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    valueText,
                    color = valueC,
                    fontSize = (16f * scale).sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                )
            }
        }

        "toggle" -> {
            val st = node.entity?.let { dash.states[it] }
            val active = Actions.isOn(st?.state)
            StatusTile(
                modifier = modifier,
                theme = theme,
                lit = active,
                tint = tileTint(node, theme),
                label = node.text ?: st?.friendly ?: node.entity.orEmpty(),
                big = if (active) "ON" else "OFF",
            ) { dash.tap(scope, node) }
        }

        "progress" -> {
            val st = node.entity?.let { dash.states[it] }
            val raw = node.attribute?.let { a -> st?.attrs?.get(a) } ?: st?.state
            val num = when (raw) {
                is Number -> raw.toFloat()
                is String -> raw.toFloatOrNull()
                else -> null
            }
            val maxV = node.max ?: 100f
            val minV = node.min ?: 0f
            val span = (maxV - minV).takeIf { it > 0f } ?: 100f
            val frac = num?.let { ((it - minV) / span).coerceIn(0f, 1f) }
            val fracAnim by animateFloatAsState(frac ?: 0f)
            val label = node.text ?: st?.friendly ?: node.entity.orEmpty()
            val valueText = num?.let { v ->
                val vs = if (v % 1f == 0f) v.toInt().toString()
                         else "%.1f".format(java.util.Locale.US, v)
                node.format?.replace("{value}", vs) ?: "$vs%"
            } ?: "\u2014"
            val tint = tileTint(node, theme)
            val pal = paletteFor(theme)

            Column(
                modifier
                    .fillMaxWidth()
                    .clip(corner)
                    .background(pal.offFill)
                    .border(1.dp, pal.offBorder, corner)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label.uppercase(),
                        color = pal.offLabel,
                        fontSize = (12.5f * scale).sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        valueText,
                        color = tint,
                        fontSize = (21f * scale).sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(7.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(2.5.dp))
                        .background(pal.track),
                ) {
                    if (fracAnim > 0.004f) {
                        Box(
                            Modifier
                                .fillMaxWidth(fracAnim)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(2.5.dp))
                                .background(tint),
                        )
                    }
                }
            }
        }

        "grid" -> {
            // Tile grid: big touch targets, "columns" per row (2-3 recommended on
            // 480x480), rows share the grid's height equally. Pad a short last row
            // so tile widths stay equal.
            val cols = (node.columns ?: 2).coerceIn(1, 4)
            val sp = (node.spacing ?: 8).dp
            val rows = node.children.chunked(cols)
            val h = node.height
            val rh = node.rowHeight
            val fillsHeight = node.weight != null || h != null
            Column(
                modifier
                    .fillMaxWidth()
                    .then(if (h != null) Modifier.height(h.dp) else Modifier)
                    .padding((node.padding ?: 0).dp),
                verticalArrangement = Arrangement.spacedBy(sp),
            ) {
                rows.forEach { row ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(
                                when {
                                    rh != null -> Modifier.height(rh.dp)
                                    fillsHeight -> Modifier.weight(1f)
                                    else -> Modifier.height(104.dp)
                                },
                            ),
                        horizontalArrangement = Arrangement.spacedBy(sp),
                    ) {
                        row.forEach { child ->
                            RenderNode(child, theme, dash, Modifier.weight(1f).fillMaxHeight(), inRow = true)
                        }
                        repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        "spacer" -> {
            if (node.height != null) {
                Spacer(modifier.fillMaxWidth().height(node.height.dp))
            } else {
                Spacer(modifier)
            }
        }

        else -> { // "label" and fallback
            val st = node.entity?.let { dash.states[it] }
            // `decimals: N` rounds a numeric state so sensors like 28.700006
            // render as 28.7. Non-numeric states pass through untouched.
            val rawState = st?.state ?: "-"
            val stateText = node.decimals?.let { d ->
                rawState.trim().toDoubleOrNull()
                    ?.let { v -> "%.${d}f".format(java.util.Locale.US, v) }
            } ?: rawState
            val text = if (node.format != null) {
                node.format
                    .replace("{state}", stateText)
                    .replace("{friendly}", st?.friendly ?: node.entity ?: "")
            } else {
                node.text ?: stateText
            }
            val align = when (node.align?.lowercase()) {
                "center" -> TextAlign.Center
                "end", "right" -> TextAlign.End
                else -> TextAlign.Start
            }
            Text(
                text,
                color = theme.textColor,
                fontSize = ((node.size ?: 16f) * scale).sp,
                maxLines = 3,
                textAlign = align,
                modifier = if (inRow && node.weight == null) modifier.wrapContentWidth() else modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun PageNav(pages: List<PageCfg>, selected: Int, theme: ThemeCfg, onSelect: (Int) -> Unit) {
    val scale = theme.fontScale.coerceIn(0.5f, 3f)
    val pal = paletteFor(theme)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(pal.navBg)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        pages.forEachIndexed { i, page ->
            val active = i == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) theme.accentColor else pal.navKey)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    page.name.uppercase(),
                    color = if (active) lerp(theme.accentColor, Color.Black, 0.88f)
                            else pal.navText,
                    fontSize = ((if (pages.size >= 5) 11f else 13f) * scale).sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    maxLines = 1,
                )
            }
        }
    }
}
