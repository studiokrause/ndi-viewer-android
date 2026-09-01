package com.lekozaur.ndiviewer

import android.graphics.Color

data class FalseColorEntry(val ire: Int, val range: String, val name: String, val hex: String, val color: Int)

object FalseColorTable {
    val entries: List<FalseColorEntry> = listOf(
        FalseColorEntry(0, "0–2", "White", "#FFFFFF", Color.parseColor("#FFFFFF")),
        FalseColorEntry(2, "2–10", "Blue", "#020346", Color.parseColor("#020346")),
        FalseColorEntry(10, "10–20", "Light Blue", "#2496FF", Color.parseColor("#2496FF")),
        FalseColorEntry(20, "20–42", "Dark Grey", "#52524F", Color.parseColor("#52524F")),
        FalseColorEntry(42, "42–48", "Bright Purple", "#FE1BFE", Color.parseColor("#FE1BFE")),
        FalseColorEntry(48, "48–52", "Medium Gray", "#7B7B7B", Color.parseColor("#7B7B7B")),
        FalseColorEntry(52, "52–58", "Green", "#1DF438", Color.parseColor("#1DF438")),
        FalseColorEntry(58, "58–78", "Light Grey", "#BAB8B6", Color.parseColor("#BAB8B6")),
        FalseColorEntry(78, "78–84", "Dark Yellow", "#AEAF15", Color.parseColor("#AEAF15")),
        FalseColorEntry(84, "84–94", "Yellow", "#F6FF19", Color.parseColor("#F6FF19")),
        FalseColorEntry(94, "94–100", "Orange", "#FF971C", Color.parseColor("#FF971C")),
        FalseColorEntry(100, "100–108", "Red", "#DE0E0D", Color.parseColor("#DE0E0D")),
    )

    // Map IRE 0..108 to color by range
    fun colorForIRE(ire: Int): Int {
        val clamped = ire.coerceIn(0, 108)
        for (i in entries.indices) {
            val cur = entries[i]
            val nextIRE = if (i + 1 < entries.size) entries[i + 1].ire else 109
            if (clamped >= cur.ire && clamped < nextIRE) return cur.color
        }
        return entries.last().color
    }

    // Luma 0..255 (where 16=0 IRE, 235=100 IRE, 255=108 IRE approx) -> IRE
    fun lumaToIRE(luma: Int): Int {
        // BT.709 limited: 16..235 maps to 0..100, 236..255 maps to 100..108
        return when {
            luma <= 16 -> 0
            luma >= 235 -> 100 + ((luma - 235) * 8 / 20).coerceIn(0, 8) // 235->100, 255->108
            else -> (luma - 16) * 100 / 219
        }
    }

    fun lumaToColor(luma: Int): Int = colorForIRE(lumaToIRE(luma))
}
