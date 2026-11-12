package dev.mahali.hatch.design

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

@DslMarker
public annotation class HatchListDsl

/** Every grouped screen renders through here, so headers, cards and notes are spaced alike. */
@Composable
public fun HatchList(continued: Boolean = false, build: HatchListScope.() -> Unit) {
    val sections = HatchListScope().apply(build).sections
    sections.forEachIndexed { i, section ->
        Revealed(section.visible) { Section(section, first = i == 0 && !continued) }
    }
}

@Composable
private fun Section(section: HatchListSection, first: Boolean) {
    run {
        when {
            section.title != null -> SectionHeader(section.title, top = if (first) 0.dp else Hatch.layout.sectionGap)
            !first -> CardSpacer()
        }
        if (section.rows.isNotEmpty()) HatchListCard {
            section.rows.forEachIndexed { j, row ->
                Revealed(row.visible) {
                    if (j > 0) { if (row.leading) HatchListDivider() else HatchListDivider(LocalListMetrics.current.padding) }
                    row.content()
                }
            }
        }
        section.footer?.let { Footnote(it) }
    }
}

@HatchListDsl
public class HatchListScope internal constructor() {
    internal val sections = mutableListOf<HatchListSection>()

    public fun section(title: String? = null, footer: String? = null, visible: Boolean = true, rows: HatchSectionScope.() -> Unit) {
        sections += HatchListSection(title, footer, HatchSectionScope().apply(rows).rows, visible)
    }

    public val titles: List<String> get() = sections.flatMap { s -> s.rows.map { it.title } }
}

@HatchListDsl
public class HatchSectionScope internal constructor() {
    internal val rows = mutableListOf<HatchListRowSpec>()

    public fun switch(title: String, checked: Boolean, subtitle: String? = null, visible: Boolean = true, onChange: (Boolean) -> Unit) {
        rows += HatchListRowSpec(title, visible = visible) { HatchSwitchRow(title, checked, onChange, subtitle) }
    }

    public fun <T> choice(
        title: String,
        value: T,
        options: List<T>,
        label: (T) -> String,
        icon: (@Composable (option: T, selected: Boolean) -> Unit)? = null,
        leading: (@Composable () -> Unit)? = null,
        onChoose: (T) -> Unit,
    ) {
        rows += HatchListRowSpec(title, leading = leading != null) { HatchChoiceRow(title, value, options, label, onChoose, icon, leading) }
    }

    public fun stepper(title: String, value: String, canDecrease: Boolean, canIncrease: Boolean, decrease: () -> Unit, increase: () -> Unit) {
        rows += HatchListRowSpec(title) { HatchStepperRow(title, value, canDecrease, canIncrease, decrease, increase) }
    }

    public fun value(title: String, value: String, mono: Boolean = false) {
        rows += HatchListRowSpec(title) { HatchValueRow(title, value, mono) }
    }

    public fun <T> segmented(title: String, value: T, options: List<T>, label: (T) -> String, icon: ((T) -> ImageVector?)? = null, onSelect: (T) -> Unit) {
        rows += HatchListRowSpec(title) { HatchSegmentedRow(title, value, options, label, onSelect, icon) }
    }

    public fun slider(title: String, value: Int, steps: IntRange, valueLabel: String, onChange: (Int) -> Unit) {
        rows += HatchListRowSpec(title) { HatchSliderRow(title, value, steps, valueLabel, onChange) }
    }

    public fun action(title: String, onClick: () -> Unit) {
        rows += HatchListRowSpec(title) { HatchListRow(title, titleColor = chrome.accent, onClick = onClick) }
    }

    public fun text(title: String, text: String, mono: Boolean = false) {
        rows += HatchListRowSpec(title) {
            Text(
                text, Modifier.padding(horizontal = Hatch.layout.cardPadding, vertical = Hatch.space.l),
                style = if (mono) Hatch.type.mono else Hatch.type.footnote, color = chrome.secondary,
            )
        }
    }

    public fun link(title: String, value: String? = null, subtitle: String? = null, onClick: () -> Unit) {
        rows += HatchListRowSpec(title) {
            HatchListRow(title, subtitle = subtitle, onClick = onClick, trailing = {
                if (value != null) Text(value, style = Hatch.type.body, color = chrome.secondary, maxLines = 1)
                RowChevron()
            })
        }
    }

    public fun row(title: String, leading: Boolean = false, content: @Composable () -> Unit) {
        rows += HatchListRowSpec(title, leading, content = content)
    }
}

internal class HatchListSection(val title: String?, val footer: String?, val rows: List<HatchListRowSpec>, val visible: Boolean = true)

internal class HatchListRowSpec(
    val title: String,
    val leading: Boolean = false,
    val visible: Boolean = true,
    val content: @Composable () -> Unit,
)
