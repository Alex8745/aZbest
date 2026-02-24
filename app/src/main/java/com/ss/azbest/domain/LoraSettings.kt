package com.ss.azbest.domain

data class LoraSettings(
    val usePreset: Boolean = true,
    val modemPreset: ModemPresetOption = ModemPresetOption.LONG_FAST,
    val overrideFrequency: Float = 0f   // 0 = не переопределять, иначе МГц
)

enum class ModemPresetOption(
    val protoValue: Int,
    val displayName: String,
    val description: String
) {
    LONG_FAST      (0, "Long Fast",       "Дальность ↑↑  Скорость ↑   (по умолчанию)"),
    MEDIUM_SLOW    (3, "Medium Slow",     "Дальность ↑   Скорость ↓"),
    MEDIUM_FAST    (4, "Medium Fast",     "Дальность ↑   Скорость ↑↑"),
    SHORT_SLOW     (5, "Short Fast",      "Дальность ↓   Скорость ↑"),
    SHORT_FAST     (6, "Short Fast",      "Дальность ↓↓  Скорость ↑↑"),
    LONG_MODERATE  (7, "Long Moderate",   "Дальность ↑↑  Скорость ↕"),
    SHORT_TURBO    (8, "Short Turbo",     "Максимальная скорость (500 кГц, не везде легально)"),
    LONG_TURBO     (9, "Long Turbo",      "Long Fast + ширина 500 кГц")
}
