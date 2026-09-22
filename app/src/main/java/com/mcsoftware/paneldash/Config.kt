package com.mcsoftware.paneldash

import org.yaml.snakeyaml.Yaml

data class ServerCfg(
    val url: String = "http://homeassistant.local:8123",
    val token: String = "",
    val tokenFile: String? = null,
    val pollSeconds: Int = 5,
)

data class OptionsCfg(
    val orientation: String = "auto",
    val dayNight: String = "sensor",     // sensor | auto (sun times) | off
    val latitude: Double? = null,        // optional: skip IP lookup (sun mode)
    val longitude: Double? = null,
    val darkBelowLux: Float = 10f,       // sensor mode: room is "dark" below this
    val lightAboveLux: Float = 40f,      // sensor mode: room is "lit" above this
    val autoBrightness: Boolean = false, // tie screen brightness to ambient lux
    val brightnessMin: Int = 10,         // darkest backlight (0-255)
    val brightnessMax: Int = 255,        // brightest backlight (0-255)
    val idleReturnSeconds: Int = 0,      // back to the first page after N s without touch (0 = off)
)

data class ThemeCfg(
    val background: String = "#0E1116",
    val surface: String = "#1B222C",
    val accent: String = "#3D8BFD",
    val text: String = "#EAF0F6",
    val radius: Int = 14,
    val fontScale: Float = 1f,
)

/** Doorbell popup: ring entity -> full-screen live camera overlay. */
data class DoorbellCfg(
    val entity: String,
    val camera: String? = null,
    val title: String = "Doorbell",
    val seconds: Int = 30,
    val sound: Boolean = false,   // chime on the panel speaker when the alert fires
    val volume: Float = 0.8f,     // per-play gain 0-1 (system volume untouched)
)

/** Wake-on-approach: dim the backlight when nobody is near, wake on approach/touch. */
data class ProximityCfg(
    val enabled: Boolean = true,
    val nearValue: Float = 2000f,   // raw reading >= this means "someone is close"
    val nearDelta: Float = 25f,     // ...or a jump this far from the resting baseline
    val awaySeconds: Int = 45,      // dim after this long with no activity
    val dimBrightness: Float = 0.03f, // backlight fraction while dimmed (0-1)
)

data class NodeCfg(
    val type: String,
    val text: String? = null,
    val size: Float? = null,
    val align: String? = null,
    val weight: Float? = null,
    val spacing: Int? = null,
    val padding: Int? = null,
    val height: Int? = null,
    val entity: String? = null,
    val action: String? = null,
    val service: String? = null,
    val data: Map<String, Any?>? = null,
    val format: String? = null,
    val attribute: String? = null,
    val min: Float? = null,
    val max: Float? = null,
    val decimals: Int? = null,   // labels: round a numeric {state} to N decimals
    val columns: Int? = null,
    val rowHeight: Int? = null,
    val sub: String? = null,
    val subEntity: String? = null,
    val highlight: Boolean? = null,
    val color: String? = null,
    // `check` element: exactly one condition style per row
    val okAbove: Float? = null,
    val okBelow: Float? = null,
    val okIs: String? = null,
    val children: List<NodeCfg> = emptyList(),
)

data class PageCfg(
    val name: String,
    val node: NodeCfg,
)

data class PanelConfig(
    val server: ServerCfg,
    val options: OptionsCfg,
    val theme: ThemeCfg,
    val themeLight: ThemeCfg?,
    val themeDark: ThemeCfg?,
    val header: NodeCfg?,
    val pages: List<PageCfg>,
    val doorbell: DoorbellCfg? = null,
    val proximity: ProximityCfg? = null,
) {
    /** Pick the theme for the given day/night state (falls back to `theme`). */
    fun themeFor(isNight: Boolean): ThemeCfg =
        (if (isNight) themeDark else themeLight) ?: theme
    // Convenience accessor: the first page (single-page configs use only this).
    val root: NodeCfg get() = pages.first().node
}

object ConfigParser {

    fun parse(text: String): PanelConfig {
        val loaded = Yaml().load<Any?>(text)
        val map = loaded as? Map<*, *> ?: throw IllegalArgumentException("YAML root must be a mapping")
        val server = map["server"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val options = map["options"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val theme = map["theme"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val pages = parsePages(map)
        val header = (map["header"] as? Map<*, *>)?.let { parseNode(it) }
        val doorbell = (map["doorbell"] as? Map<*, *>)?.let { d ->
            val e = (d["entity"] as? String)?.trim()?.ifBlank { null } ?: return@let null
            DoorbellCfg(
                entity = e,
                camera = (d["camera"] as? String)?.trim()?.ifBlank { null },
                title = (d["title"] as? String)?.trim()?.ifBlank { null } ?: "Doorbell",
                seconds = ((d["seconds"] as? Number)?.toInt() ?: 30).coerceIn(5, 300),
                sound = (d["sound"] as? Boolean) ?: false,
                volume = ((d["volume"] as? Number)?.toFloat() ?: 0.8f).coerceIn(0f, 1f),
            )
        }
        val proximity = (map["proximity"] as? Map<*, *>)?.let { p ->
            ProximityCfg(
                enabled = (p["enabled"] as? Boolean) ?: true,
                nearValue = (p["near_value"] as? Number)?.toFloat() ?: 2000f,
                nearDelta = (p["near_delta"] as? Number)?.toFloat() ?: 6f,
                awaySeconds = ((p["away_seconds"] as? Number)?.toInt() ?: 45).coerceIn(5, 3600),
                dimBrightness = ((p["dim_brightness"] as? Number)?.toFloat() ?: 0.03f)
                    .coerceIn(0.005f, 1f),
            )
        }
        fun parseTheme(m: Map<*, *>?): ThemeCfg? = m?.let {
            ThemeCfg(
                background = strOr(it, "background", "#0E1116"),
                surface = strOr(it, "surface", "#1B222C"),
                accent = strOr(it, "accent", "#3D8BFD"),
                text = strOr(it, "text", "#EAF0F6"),
                radius = (it["radius"] as? Number)?.toInt() ?: 14,
                fontScale = (it["font_scale"] as? Number)?.toFloat() ?: 1f,
            )
        }
        return PanelConfig(
            server = ServerCfg(
                url = (server["url"] as? String)?.trim()?.trimEnd('/')?.ifBlank { null }
                    ?: "http://homeassistant.local:8123",
                token = (server["token"] as? String)?.trim() ?: "",
                tokenFile = (server["token_file"] as? String)?.trim()?.ifBlank { null },
                pollSeconds = ((server["poll_seconds"] as? Number)?.toInt() ?: 5).coerceIn(1, 120),
            ),
            options = OptionsCfg(
                orientation = (options["orientation"] as? String)?.trim()?.lowercase() ?: "auto",
                dayNight = (options["daynight"] as? String)?.trim()?.lowercase() ?: "sensor",
                latitude = (options["latitude"] as? Number)?.toDouble(),
                longitude = (options["longitude"] as? Number)?.toDouble(),
                darkBelowLux = (options["dark_below_lux"] as? Number)?.toFloat() ?: 10f,
                lightAboveLux = (options["light_above_lux"] as? Number)?.toFloat() ?: 40f,
                autoBrightness = (options["auto_brightness"] as? Boolean) ?: false,
                brightnessMin = (options["brightness_min"] as? Number)?.toInt() ?: 10,
                brightnessMax = (options["brightness_max"] as? Number)?.toInt() ?: 255,
                idleReturnSeconds = ((options["idle_return_seconds"] as? Number)?.toInt() ?: 0)
                    .coerceIn(0, 86_400),
            ),
            theme = ThemeCfg(
                background = strOr(theme, "background", "#0E1116"),
                surface = strOr(theme, "surface", "#1B222C"),
                accent = strOr(theme, "accent", "#3D8BFD"),
                text = strOr(theme, "text", "#EAF0F6"),
                radius = (theme["radius"] as? Number)?.toInt() ?: 14,
                fontScale = (theme["font_scale"] as? Number)?.toFloat() ?: 1f,
            ),
            themeLight = parseTheme(map["theme_light"] as? Map<*, *>),
            themeDark = parseTheme(map["theme_dark"] as? Map<*, *>),
            header = header,
            pages = pages,
            doorbell = doorbell,
            proximity = proximity,
        )
    }

    private fun parsePages(map: Map<*, *>): List<PageCfg> {
        val raw = map["pages"] as? List<*>
        if (raw != null) {
            val pages = raw.mapIndexedNotNull { idx, item ->
                val m = item as? Map<*, *> ?: return@mapIndexedNotNull null
                PageCfg(
                    name = (m["name"] as? String)?.trim()?.ifBlank { null } ?: "Page ${idx + 1}",
                    node = parseNode(m),
                )
            }
            if (pages.isEmpty()) throw IllegalArgumentException("'pages' is empty")
            return pages
        }
        val root = map["root"] as? Map<*, *>
            ?: throw IllegalArgumentException("Missing 'root' or 'pages' block")
        return listOf(PageCfg("Main", parseNode(root)))
    }

    private fun strOr(m: Map<*, *>, key: String, def: String): String =
        (m[key] as? String)?.trim()?.ifBlank { null } ?: def

    private fun parseNode(m: Map<*, *>): NodeCfg {
        @Suppress("UNCHECKED_CAST")
        val data = m["data"] as? Map<String, Any?>
        val children = (m["children"] as? List<*>)?.mapNotNull { c ->
            (c as? Map<*, *>)?.let { parseNode(it) }
        } ?: emptyList()
        return NodeCfg(
            type = (m["type"] as? String)?.trim()?.lowercase() ?: "label",
            text = m["text"] as? String,
            size = (m["size"] as? Number)?.toFloat(),
            align = m["align"] as? String,
            weight = (m["weight"] as? Number)?.toFloat(),
            spacing = (m["spacing"] as? Number)?.toInt(),
            padding = (m["padding"] as? Number)?.toInt(),
            height = (m["height"] as? Number)?.toInt(),
            entity = (m["entity"] as? String)?.trim()?.ifBlank { null },
            action = (m["action"] as? String)?.trim()?.lowercase()?.ifBlank { null },
            service = (m["service"] as? String)?.trim()?.ifBlank { null },
            data = data,
            format = m["format"] as? String,
            attribute = (m["attribute"] as? String)?.trim()?.ifBlank { null },
            min = (m["min"] as? Number)?.toFloat(),
            max = (m["max"] as? Number)?.toFloat(),
            decimals = (m["decimals"] as? Number)?.toInt()?.coerceIn(0, 4),
            columns = (m["columns"] as? Number)?.toInt()?.coerceIn(1, 4),
            rowHeight = (m["row_height"] as? Number)?.toInt(),
            sub = m["sub"] as? String,
            subEntity = (m["sub_entity"] as? String)?.trim()?.ifBlank { null },
            highlight = m["highlight"] as? Boolean,
            color = (m["color"] as? String)?.trim()?.ifBlank { null },
            okAbove = (m["ok_above"] as? Number)?.toFloat(),
            okBelow = (m["ok_below"] as? Number)?.toFloat(),
            okIs = (m["ok_is"] as? String)?.trim()?.ifBlank { null },
            children = children,
        )
    }
}
