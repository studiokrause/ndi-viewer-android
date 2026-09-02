package com.lekozaur.ndiviewer

import android.graphics.Color

data class FalseColorEntry(val ire: Int, val mv: Int, val range: String, val name: String, val hex: String, val color: Int)

object FalseColorTable {
    val entries: List<FalseColorEntry> = listOf(
        FalseColorEntry(0, 0, "0–5", "Bright Purple", "#D938FF", Color.parseColor("#D938FF")),
        FalseColorEntry(5, 35, "5–10", "Blue", "#0000FF", Color.parseColor("#0000FF")),
        FalseColorEntry(10, 70, "10–15", "Light Blue", "#548CFF", Color.parseColor("#548CFF")),
        FalseColorEntry(15, 105, "15–20", "Light Blue", "#548CFF", Color.parseColor("#548CFF")),
        FalseColorEntry(20, 140, "20–25", "Dark Gray", "#4C4C4C", Color.parseColor("#4C4C4C")),
        FalseColorEntry(25, 175, "25–30", "Dark Gray", "#4C4C4C", Color.parseColor("#4C4C4C")),
        FalseColorEntry(30, 210, "30–35", "Dark Gray", "#4C4C4C", Color.parseColor("#4C4C4C")),
        FalseColorEntry(35, 245, "35–40", "Dark Gray", "#4C4C4C", Color.parseColor("#4C4C4C")),
        FalseColorEntry(40, 280, "40–45", "Dark Gray", "#4C4C4C", Color.parseColor("#4C4C4C")),
        FalseColorEntry(45, 315, "45–50", "Green", "#99FF00", Color.parseColor("#99FF00")),
        FalseColorEntry(50, 350, "50–55", "Medium Gray", "#7F7F7F", Color.parseColor("#7F7F7F")),
        FalseColorEntry(55, 385, "55–60", "Pink", "#F29E9E", Color.parseColor("#F29E9E")),
        FalseColorEntry(60, 420, "60–65", "Light Gray", "#B2B2B2", Color.parseColor("#B2B2B2")),
        FalseColorEntry(65, 455, "65–70", "Light Gray", "#B2B2B2", Color.parseColor("#B2B2B2")),
        FalseColorEntry(70, 490, "70–75", "Light Gray", "#B2B2B2", Color.parseColor("#B2B2B2")),
        FalseColorEntry(75, 525, "75–80", "Light Gray", "#B2B2B2", Color.parseColor("#B2B2B2")),
        FalseColorEntry(80, 560, "80–85", "Dark Yellow", "#FFFF00", Color.parseColor("#FFFF00")),
        FalseColorEntry(85, 595, "85–90", "Yellow", "#FFFF00", Color.parseColor("#FFFF00")),
        FalseColorEntry(90, 630, "90–95", "Yellow", "#FFFF00", Color.parseColor("#FFFF00")),
        FalseColorEntry(95, 665, "95–100", "Orange", "#E57F00", Color.parseColor("#E57F00")),
        FalseColorEntry(100, 700, "100+", "Red", "#E53300", Color.parseColor("#E53300")),
    )

    fun colorForIRE(ire: Int): Int {
        val clamped = ire.coerceIn(0, 108)
        for (i in entries.indices) {
            val cur = entries[i]
            val nextIRE = if (i + 1 < entries.size) entries[i + 1].ire else 109
            if (clamped >= cur.ire && clamped < nextIRE) return cur.color
        }
        return entries.last().color
    }

    fun lumaToIRE(luma: Int): Int {
        return when {
            luma <= 16 -> 0
            luma >= 235 -> 100 + ((luma - 235) * 8 / 20).coerceIn(0, 8)
            else -> (luma - 16) * 100 / 219
        }
    }

    fun lumaToColor(luma: Int): Int = colorForIRE(lumaToIRE(luma))
}
