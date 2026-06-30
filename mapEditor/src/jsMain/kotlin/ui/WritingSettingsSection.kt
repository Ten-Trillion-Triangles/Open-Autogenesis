package ui

import io.kvision.core.CssSize
import io.kvision.core.Overflow
import io.kvision.core.UNIT
import io.kvision.panel.SimplePanel
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import structs.InjectableCriterion
import structs.RuleCategory

/**
 * Standalone DOM component that owns its own HTML element.
 * Completely bypasses KVision's virtual DOM rendering.
 * Use [renderInto] to attach to a parent element.
 */
class WritingSettingsCategorySection(
    val widthPx: Int,
    val rowHeightPx: Int,
    val spacingPx: Int,
    val categories: List<RuleCategory>,
    val categorySpinnerValues: MutableMap<Int, Int>,
    val onCategoryChange: (index: Int, newValue: Int) -> Unit
) {
    private var rootElement: HTMLElement? = null

    fun renderInto(parent: HTMLElement) {
        val totalHeight = categories.size * (rowHeightPx + spacingPx)
        val el = document.createElement("div") as HTMLElement
        el.style.display = "block"
        el.style.width = "${widthPx}px"
        el.style.minHeight = "0px"
        el.style.height = "${totalHeight}px"
        el.style.setProperty("overflow", "hidden")
        el.style.position = "relative"

        categories.forEachIndexed { index, category ->
            val top = index * (rowHeightPx + spacingPx)
            val row = document.createElement("div") as HTMLElement
            row.style.position = "absolute"
            row.style.top = "${top}px"
            row.style.left = "0"
            row.style.display = "flex"
            row.style.alignItems = "center"
            row.style.justifyContent = "space-between"
            row.style.width = "${widthPx}px"
            row.style.height = "${rowHeightPx}px"
            row.style.background = "#1a1f30"
            row.style.borderRadius = "8px"
            row.style.padding = "0 10px"
            row.style.boxSizing = "border-box"

            val label = document.createElement("span") as HTMLElement
            label.style.fontSize = "16px"
            label.style.fontWeight = "bold"
            label.style.color = "#f4f6fb"
            label.style.margin = "0"
            label.textContent = category.name.replace("_", " ").replaceFirstChar { it.uppercase() }
            row.appendChild(label)

            val right = document.createElement("div") as HTMLElement
            right.style.display = "flex"
            right.style.alignItems = "center"
            right.style.setProperty("gap", "5px")

            val chanceLbl = document.createElement("span") as HTMLElement
            chanceLbl.style.fontSize = "14px"
            chanceLbl.style.color = "#a0a8c0"
            chanceLbl.style.margin = "0"
            chanceLbl.textContent = "Chance:"
            right.appendChild(chanceLbl)

            val spinner = document.createElement("input") as HTMLInputElement
            spinner.type = "number"
            spinner.min = "0"
            spinner.max = "100"
            spinner.step = "5"
            // Use the tracking map value (pre-populated from loaded config) so rendered values
            // match what was actually loaded, not the hardcoded defaults in the category list.
            val renderedValue = categorySpinnerValues[index] ?: category.chancePercent
            console.log("[WSS] render category[$index] '${category.name}': spinnerMapValue=${categorySpinnerValues[index]}, category.chancePercent=${category.chancePercent}, rendered=$renderedValue")
            spinner.value = renderedValue.toString()
            spinner.style.width = "70px"
            spinner.style.background = "#0d1117"
            spinner.style.color = "#f4f6fb"
            spinner.style.border = "1px solid #30363d"
            spinner.style.borderRadius = "4px"
            spinner.style.padding = "4px 8px"
            spinner.style.fontSize = "14px"
            spinner.style.textAlign = "center"
            val capturedIndex = index
            spinner.addEventListener("change", { e: Event ->
                val target = e.target as? HTMLInputElement
                val newValue = target?.value?.toIntOrNull() ?: return@addEventListener
                categorySpinnerValues[capturedIndex] = newValue
                onCategoryChange(capturedIndex, newValue)
            })
            right.appendChild(spinner)

            val pctLbl = document.createElement("span") as HTMLElement
            pctLbl.style.fontSize = "14px"
            pctLbl.style.color = "#a0a8c0"
            pctLbl.style.margin = "0"
            pctLbl.textContent = "%"
            right.appendChild(pctLbl)

            row.appendChild(right)
            el.appendChild(row)
        }

        rootElement?.let { parent.removeChild(it) }
        parent.appendChild(el)
        rootElement = el
    }
}

/**
 * Standalone DOM component for selection criteria, bypassing KVision's flexbox.
 */
class WritingSettingsCriteriaSection(
    val widthPx: Int,
    val rowHeightPx: Int,
    val spacingPx: Int,
    val criteria: List<InjectableCriterion>,
    val criteriaSpinnerValues: MutableMap<Int, Int>,
    val criteriaEnabledStates: MutableMap<Int, Boolean>,
    val onCriterionChange: (index: Int, newValue: Int) -> Unit,
    val onCriterionToggle: (index: Int, isEnabled: Boolean) -> Unit
) {
    private var rootElement: HTMLElement? = null

    fun renderInto(parent: HTMLElement) {
        val totalHeight = criteria.size * (rowHeightPx + spacingPx)
        val el = document.createElement("div") as HTMLElement
        el.style.display = "block"
        el.style.width = "${widthPx}px"
        el.style.minHeight = "0px"
        el.style.height = "${totalHeight}px"
        el.style.setProperty("overflow", "hidden")
        el.style.position = "relative"

        criteria.forEachIndexed { index, criterion ->
            val top = index * (rowHeightPx + spacingPx)
            val isEnabled = criteriaEnabledStates[index] ?: (criterion.chancePercent > 0)
            val rowBg = if (isEnabled) "#1a1f30" else "#151922"

            val row = document.createElement("div") as HTMLElement
            row.style.position = "absolute"
            row.style.top = "${top}px"
            row.style.left = "0"
            row.style.display = "flex"
            row.style.alignItems = "center"
            row.style.justifyContent = "space-between"
            row.style.width = "${widthPx}px"
            row.style.height = "${rowHeightPx}px"
            row.style.background = rowBg
            row.style.borderRadius = "8px"
            row.style.padding = "0 10px"
            row.style.boxSizing = "border-box"

            val left = document.createElement("div") as HTMLElement
            left.style.display = "flex"
            left.style.alignItems = "center"
            left.style.setProperty("gap", "10px")
            left.style.flex = "1"
            left.style.minWidth = "0"

            val checkbox = document.createElement("input") as HTMLInputElement
            checkbox.type = "checkbox"
            checkbox.checked = isEnabled
            checkbox.style.width = "18px"
            checkbox.style.height = "18px"
            checkbox.style.flexShrink = "0"
            checkbox.style.cursor = "pointer"
            val capturedIndex = index
            checkbox.addEventListener("change", { e: Event ->
                val target = e.target as? HTMLInputElement ?: return@addEventListener
                val checked = target.checked
                criteriaEnabledStates[capturedIndex] = checked
                val newChance = if (checked) 100 else 0
                criteriaSpinnerValues[capturedIndex] = newChance
                row.style.background = if (checked) "#1a1f30" else "#151922"
                onCriterionToggle(capturedIndex, checked)
            })
            left.appendChild(checkbox)

            val desc = document.createElement("span") as HTMLElement
            desc.style.fontSize = "14px"
            desc.style.color = "#f4f6fb"
            desc.style.margin = "0"
            desc.style.whiteSpace = "nowrap"
            desc.style.setProperty("overflow", "hidden")
            desc.style.textOverflow = "ellipsis"
            desc.textContent = criterion.description
            left.appendChild(desc)
            row.appendChild(left)

            val right = document.createElement("div") as HTMLElement
            right.style.display = "flex"
            right.style.alignItems = "center"
            right.style.setProperty("gap", "5px")
            right.style.flexShrink = "0"

            val chanceLbl = document.createElement("span") as HTMLElement
            chanceLbl.style.fontSize = "14px"
            chanceLbl.style.color = "#a0a8c0"
            chanceLbl.style.margin = "0"
            chanceLbl.textContent = "Chance:"
            right.appendChild(chanceLbl)

            val spinner = document.createElement("input") as HTMLInputElement
            spinner.type = "number"
            spinner.min = "0"
            spinner.max = "100"
            spinner.step = "5"
            // Use the tracking map value (pre-populated from loaded config) so rendered values
            // match what was actually loaded. Falls back to criterion.chancePercent for untouched spinners.
            val renderedValue = criteriaSpinnerValues[index] ?: criterion.chancePercent
            spinner.value = renderedValue.toString()
            console.log("[WSS] render criterion[$index] '${criterion.description.take(30)}': spinnerMapValue=${criteriaSpinnerValues[index]}, criterion.chancePercent=${criterion.chancePercent}, rendered=$renderedValue")
            spinner.style.width = "70px"
            spinner.style.background = "#0d1117"
            spinner.style.color = "#f4f6fb"
            spinner.style.border = "1px solid #30363d"
            spinner.style.borderRadius = "4px"
            spinner.style.padding = "4px 8px"
            spinner.style.fontSize = "14px"
            spinner.style.textAlign = "center"
            spinner.addEventListener("change", { e: Event ->
                val target = e.target as? HTMLInputElement
                val newValue = target?.value?.toIntOrNull() ?: return@addEventListener
                criteriaSpinnerValues[capturedIndex] = newValue
                criteriaEnabledStates[capturedIndex] = newValue > 0
                checkbox.checked = newValue > 0
                row.style.background = if (newValue > 0) "#1a1f30" else "#151922"
                onCriterionChange(capturedIndex, newValue)
            })
            right.appendChild(spinner)

            val pctLbl = document.createElement("span") as HTMLElement
            pctLbl.style.fontSize = "14px"
            pctLbl.style.color = "#a0a8c0"
            pctLbl.style.margin = "0"
            pctLbl.textContent = "%"
            right.appendChild(pctLbl)

            row.appendChild(right)
            el.appendChild(row)
        }

        rootElement?.let { parent.removeChild(it) }
        parent.appendChild(el)
        rootElement = el
    }
}
