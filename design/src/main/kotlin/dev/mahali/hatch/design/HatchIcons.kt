package dev.mahali.hatch.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp
import dev.mahali.hatch.core.HostKind

/** Line icons from the Paper designs' SVG paths. */
public object HatchIcons {
    public const val STROKE_DP: Float = 1.6f

    // Host kinds draw thinner than the other icons: a device outline at 1.6 read heavy beside its name.
    private const val HOST_STROKE: Float = 1.25f * 24f / 22f

    public val Settings: ImageVector = line(
        "Settings",
        "M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z",
        "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1Z",
        // The gear spans 22 of 24 units and the eye 20: drawn at 0.9 they read the same size side by side.
        scale = 0.9f,
    )
    public val Eye: ImageVector = line("Eye", "M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12Z", "M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z")
    /** Activity uses it when the view is on. */
    public val EyeFilled: ImageVector = filled(
        "EyeFilled",
        "M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12ZM15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z",
        evenOdd = true,
    )
    public val Search: ImageVector = line("Search", "M18 11a7 7 0 1 1-14 0 7 7 0 0 1 14 0Z", "m20 20-3.5-3.5")
    public val Sidebar: ImageVector = line(
        "Sidebar",
        "M6 4.5h12a3 3 0 0 1 3 3v9a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3v-9a3 3 0 0 1 3-3Z",
        "M9 4.5v15M5.5 8h1.5M5.5 11h1.5",
    )
    public val Laptop: ImageVector = line(
        "Laptop", "M5.5 5h13A1.5 1.5 0 0 1 20 6.5v8a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 14.5v-8A1.5 1.5 0 0 1 5.5 5Z", "M2 19h20", stroke = HOST_STROKE,
    )
    public val Desktop: ImageVector = line(
        "Desktop",
        "M4.5 4h15A1.5 1.5 0 0 1 21 5.5v9a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 14.5v-9A1.5 1.5 0 0 1 4.5 4Z",
        "M9 20h6M12 16v4", stroke = HOST_STROKE,
    )
    public val Globe: ImageVector = line(
        "Globe", "M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z",
        "M3 12h18M12 3c-5 5-5 13 0 18M12 3c5 5 5 13 0 18M5 7h14M5 17h14", stroke = HOST_STROKE,
    )
    public val Server: ImageVector = line(
        "Server", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z",
        "M3 9h18M3 15h18M17 6h1M17 12h1M17 18h1", stroke = HOST_STROKE,
    )
    public val Cloud: ImageVector = line(
        "Cloud", "M6.5 18a4.5 4.5 0 0 1-.6-9 6.5 6.5 0 0 1 12.6 1.5 3.8 3.8 0 0 1-.3 7.5Z", stroke = HOST_STROKE,
    )
    public val MacMini: ImageVector = line(
        "MacMini", "M4 8h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2Z",
        "M5 11v2M8 11v2M18 13h1", stroke = HOST_STROKE,
    )
    public val MacStudio: ImageVector = line(
        "MacStudio", "M4 6h16a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2Z",
        "M5 13v2M8 13v2M18 15h1", stroke = HOST_STROKE,
    )

    public val AddCircle: ImageVector = line("AddCircle", "M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z", "M12 8v8M8 12h8")
    public val Minus: ImageVector = line("Minus", "M5 12h14")
    public val Plus: ImageVector = line("Plus", "M12 5v14M5 12h14")
    public val Close: ImageVector = line("Close", "M6 6l12 12M18 6 6 18")
    public val Check: ImageVector = line("Check", "M5 12.5l4.4 4.4L19 7.3")
    public val Pencil: ImageVector = line("Pencil", "M4 18l1-4L14.5 4.5l3 3L8 17l-4 1Z", grid = 22f)
    public val Trash: ImageVector = line("Trash", "M4 6h14M9 6V4h4v2M6 6l1 12h8l1-12M9.5 9v6M12.5 9v6", grid = 22f)
    public val Reconnect: ImageVector = line("Reconnect", "M17.5 11A6.5 6.5 0 1 1 15 5.9M15 2.5v3.6h-3.6", grid = 22f)
    public val CollapseOthers: ImageVector = line("CollapseOthers", "M6 8l5 5 5-5M6 4h10", grid = 22f)
    public val Split: ImageVector = line(
        "Split", "M5 4h12a2.5 2.5 0 0 1 2.5 2.5v9a2.5 2.5 0 0 1-2.5 2.5H5a2.5 2.5 0 0 1-2.5-2.5v-9A2.5 2.5 0 0 1 5 4Z", "M11 4v14",
        grid = 22f,
    )
    public val Move: ImageVector = line("Move", "M4 7h14l-3-3M18 15H4l3 3", grid = 22f)
    public val SplitBelow: ImageVector = line("SplitBelow", "M3 4h18v16H3Z", "M3 12h18")
    public val ZoomPane: ImageVector = line("ZoomPane", "M8 3H3v5M16 3h5v5M3 16v5h5M21 16v5h-5")
    public val RestorePanes: ImageVector = line("RestorePanes", "M3 3h18v18H3Z", "M12 3v18M3 12h18")

    private const val Frame = "M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2Z"
    public val LayoutAuto: ImageVector = line("LayoutAuto", Frame, "M11 4v16M11 12h10")
    public val LayoutFocus: ImageVector = line("LayoutFocus", Frame, "M8 8.5h8v7H8Z")
    public val LayoutFit: ImageVector = line("LayoutFit", Frame, "M12 4v16M3 12h18")
    public val LayoutColumns: ImageVector = line(
        "LayoutColumns", "M19 4H5a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h14", "M9 4v16M15 4v16", "M21 7.5v1M21 11.5v1M21 15.5v1",
    )

    public val Sessions: ImageVector = line(
        "Sessions", "M5 7.5h12a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2Z", "M5 4.5h12M7.5 2h7",
        grid = 22f,
    )
    public val OpenExternal: ImageVector = line(
        "OpenExternal", "M9 4H5a1 1 0 0 0-1 1v12a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-4M12 4h6v6M18 4l-8 8", grid = 22f,
    )
    public val Copy: ImageVector = line(
        "Copy", "M9 7h7a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2Z", "M4 14V5a1 1 0 0 1 1-1h9",
        grid = 22f,
    )
    public val SelectText: ImageVector = line("SelectText", "M8 4h6M8 18h6M11 4v14", grid = 22f)
    public val DragHandle: ImageVector = line("DragHandle", "M4 7h14M4 11h14M4 15h14", grid = 22f)
    public val Picture: ImageVector = line(
        "Picture",
        "M6 4.5h12A2.5 2.5 0 0 1 20.5 7v10a2.5 2.5 0 0 1-2.5 2.5H6A2.5 2.5 0 0 1 3.5 17V7A2.5 2.5 0 0 1 6 4.5Z",
        "M4 17l5-4.5 3.5 3 3-2.5 4.5 4",
        filled = listOf(circle(9f, 10f, 1.8f)),
    )
    public val Qr: ImageVector = line(
        "Qr", "M3 3h6v6H3zM15 3h6v6h-6zM3 15h6v6H3z", "M15 15h2v2h-2zM19 19h2v2h-2zM19 15h2M15 21h2",
        cap = StrokeCap.Butt, join = StrokeJoin.Miter,
    )

    public val Info: ImageVector = line("Info", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0Z", "M12 11v5.5M12 7.8v.3")
    public val Alert: ImageVector = line("Alert", "M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z", "M12 7.5v5.5M12 16.2v.3")
    public val Help: ImageVector = line(
        "Help", "M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z", "M9.5 9.4a2.6 2.6 0 1 1 3.5 2.4c-.7.3-1 .8-1 1.5v.4M12 16.6v.3",
    )

    public val Remove: ImageVector = filled("Remove", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0Z M7.5 11.1h9v1.8h-9Z", evenOdd = true)

    public val ChevronLeft: ImageVector = line("ChevronLeft", "M15 4.5l-7.5 7.5 7.5 7.5")
    public val ChevronRight: ImageVector = line("ChevronRight", "M9 4.5l7.5 7.5-7.5 7.5", size = 18f)
    public val ChevronUp: ImageVector = line("ChevronUp", "M6 15l6-6 6 6", size = 18f)
    public val ChevronDown: ImageVector = line("ChevronDown", "M6 9l6 6 6-6", size = 18f)

    public val ChevronUpDown: ImageVector = line("ChevronUpDown", "M8 9l4-4 4 4M8 15l4 4 4-4", size = 16f)

    public val TextSize: ImageVector = line("TextSize", "M2.5 18l3.5-9 3.5 9M3.8 15h4.4 M11.5 18l4.5-12 4.5 12M13.2 14h5.6", size = 16f)

    public val LineHeight: ImageVector = line("LineHeight", "M5 4v16M2.5 6.5 5 4l2.5 2.5M2.5 17.5 5 20l2.5-2.5 M11 6h10M11 12h10M11 18h10", size = 16f)

    public val MoreHorizontal: ImageVector = filled("MoreHorizontal", circle(5f, 12f, 2f), circle(12f, 12f, 2f), circle(19f, 12f, 2f))

    /** Built in real colours, so it is drawn untinted. */
    public fun newSession(stroke: Color, card: Color): ImageVector = build("NewSession", 22f, 22f) {
        val disc = "M9 17a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z"
        stroke("M7.5 7h9A2.5 2.5 0 0 1 19 9.5v6a2.5 2.5 0 0 1-2.5 2.5h-9A2.5 2.5 0 0 1 5 15.5v-6A2.5 2.5 0 0 1 7.5 7Z", STROKE_DP, stroke)
        stroke("M7 4.5h10", STROKE_DP, stroke)
        fill(disc, color = card)
        stroke(disc, STROKE_DP, stroke)
        stroke("M5 15v4M3 17h4", STROKE_DP, stroke)
    }

    /** Built in real colours, so it is drawn untinted. */
    public fun addHost(accent: Color, card: Color): ImageVector = build("AddHost", 24f, 22f) {
        val w = STROKE_DP * 24f / 22f
        val disc = "M1 18.5a4.5 4.5 0 1 0 9 0a4.5 4.5 0 1 0-9 0Z"
        stroke("M5 11a8 8 0 1 0 16 0a8 8 0 1 0-16 0Z", w, accent)
        stroke("M5 11h16M13 3c2.2 2.4 3.2 5 3.2 8s-1 5.6-3.2 8c-2.2-2.4-3.2-5-3.2-8s1-5.6 3.2-8Z", w, accent)
        fill(disc, color = card)
        stroke(disc, w, accent)
        stroke("M5.5 16.5v4M3.5 18.5h4", w, accent)
    }

    public fun line(
        name: String,
        vararg paths: String,
        grid: Float = 24f,
        size: Float = 22f,
        stroke: Float = STROKE_DP * grid / size,
        filled: List<String> = emptyList(),
        cap: StrokeCap = StrokeCap.Round,
        join: StrokeJoin = StrokeJoin.Round,
        scale: Float = 1f,
    ): ImageVector = build(name, grid, size) {
        group(scaleX = scale, scaleY = scale, pivotX = grid / 2, pivotY = grid / 2) {
            for (d in paths) stroke(d, stroke / scale, cap = cap, join = join)
            for (d in filled) fill(d)
        }
    }

    public fun filled(name: String, vararg paths: String, evenOdd: Boolean = false): ImageVector =
        build(name, 24f, 22f) { for (d in paths) fill(d, evenOdd) }

    public fun circle(cx: Float, cy: Float, r: Float): String = "M${cx - r} ${cy}a$r $r 0 1 0 ${r * 2} 0a$r $r 0 1 0 ${-r * 2} 0"

    private fun badge(name: String, paths: ImageVector.Builder.() -> Unit) = build(name, 24f, 17f, paths)

    private fun build(name: String, grid: Float, size: Float, paths: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, size.dp, size.dp, grid, grid).apply(paths).build()

    private fun ImageVector.Builder.stroke(
        d: String,
        width: Float,
        color: Color = Color.Black,
        cap: StrokeCap = StrokeCap.Round,
        join: StrokeJoin = StrokeJoin.Round,
    ) {
        addPath(PathParser().parsePathString(d).toNodes(), stroke = SolidColor(color), strokeLineWidth = width, strokeLineCap = cap, strokeLineJoin = join)
    }

    private fun ImageVector.Builder.fill(d: String, evenOdd: Boolean = false, color: Color = Color.Black) {
        addPath(
            PathParser().parsePathString(d).toNodes(), fill = SolidColor(color),
            pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
        )
    }
}

/** Shared with the host picker, Remote Hosts and session headers. */
public val HostKind.icon: ImageVector
    get() = when (this) {
        HostKind.Globe -> HatchIcons.Globe
        HostKind.Server -> HatchIcons.Server
        HostKind.Cloud -> HatchIcons.Cloud
        HostKind.MacBook -> HatchIcons.Laptop
        HostKind.MacMini -> HatchIcons.MacMini
        HostKind.MacStudio -> HatchIcons.MacStudio
        HostKind.Desktop -> HatchIcons.Desktop
    }
