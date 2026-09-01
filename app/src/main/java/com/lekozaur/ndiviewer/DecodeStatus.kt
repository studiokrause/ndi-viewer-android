package com.lekozaur.ndiviewer

enum class DecodeStatus {
    UNKNOWN, // szary - nie sprawdzono
    GREEN,   // zielony - na pewno zdekoduje
    YELLOW,  // żółty - być może
    RED      // czerwony - na pewno nie
}

object DecodeClassifier {
    private fun fcc(a: Char, b: Char, c: Char, d: Char): Int =
        (a.code and 0xFF) or ((b.code and 0xFF) shl 8) or ((c.code and 0xFF) shl 16) or ((d.code and 0xFF) shl 24)

    // Green: w pełni obsługiwane w ndi_jni.cpp (RGBA/BGRA/UYVY + I420/YV12/NV12/UYVA)
    private val GREEN_FCCS = setOf(
        fcc('R','G','B','A'), fcc('R','G','B','X'),
        fcc('B','G','R','A'), fcc('B','G','R','X'),
        fcc('U','Y','V','Y'), fcc('U','Y','V','A'),
        fcc('I','4','2','0'), fcc('Y','V','1','2'), fcc('N','V','1','2')
    )
    // Yellow: teoretycznie uncompressed ale niezaimplementowane / częściowo (16-bit)
    private val YELLOW_FCCS = setOf(
        fcc('P','2','1','6'), fcc('P','A','1','6')
    )
    // Red: znane skompresowane (HX / SpeedHQ / H.264/H.265)
    private val RED_FCCS = setOf(
        fcc('H','2','6','4'), fcc('H','2','6','5'),
        fcc('A','V','C','1'), fcc('H','E','V','C'),
        fcc('S','H','Q','0'), fcc('S','H','Q','2'), fcc('S','H','Q','7'),
        fcc('S','H','Q','A'), fcc('S','H','Q','X'),
        fcc('H','X','I','V'), fcc('H','X','L','P')
    )

    fun fromFourCC(fourcc: Int): DecodeStatus {
        return when {
            GREEN_FCCS.contains(fourcc) -> DecodeStatus.GREEN
            YELLOW_FCCS.contains(fourcc) -> DecodeStatus.YELLOW
            RED_FCCS.contains(fourcc) -> DecodeStatus.RED
            // Any other non-zero is likely compressed -> red
            fourcc != 0 -> DecodeStatus.RED
            else -> DecodeStatus.YELLOW
        }
    }

    fun fromNameHeuristic(name: String): DecodeStatus {
        val n = name.uppercase()
        // HX / H264 markers are strong red signal even before probing
        val redMarkers = listOf("HX", "H.264", "H264", "H.265", "H265", "SHQ", "SPEEDHQ", "HEVC", "AVC")
        if (redMarkers.any { it in n }) return DecodeStatus.RED
        return DecodeStatus.UNKNOWN
    }
}
