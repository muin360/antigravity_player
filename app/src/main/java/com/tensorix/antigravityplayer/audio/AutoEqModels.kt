package com.tensorix.antigravityplayer.audio

/**
 * AutoEQ Headphone Calibration Models & Curated Audiophile Database
 * Based on Jaakko Pasanen's AutoEQ project and Harman Target Curves.
 */

data class AutoEqBand(
    val filterType: Int, // 0 = PEAKING_EQ, 1 = LOW_SHELF, 2 = HIGH_SHELF, 3 = LOW_PASS, 4 = HIGH_PASS
    val frequencyHz: Double,
    val qFactor: Double,
    val gainDb: Double,
    val isEnabled: Boolean = true
)

data class AutoEqProfile(
    val id: String,
    val brand: String,
    val model: String,
    val targetCurve: String,
    val type: HeadphoneType,
    val preampDb: Double,
    val bands: List<AutoEqBand>
) {
    val displayName: String get() = "$brand $model"
}

enum class HeadphoneType(val displayName: String) {
    OVER_EAR("Over-Ear"),
    IN_EAR("In-Ear (IEM)"),
    WIRELESS("True Wireless (TWS)"),
    EARBUDS("Earbuds")
}

object AutoEqDatabase {

    val profiles: List<AutoEqProfile> = listOf(
        // 1. Sennheiser HD 800 S
        AutoEqProfile(
            id = "sennheiser_hd800s",
            brand = "Sennheiser",
            model = "HD 800 S",
            targetCurve = "Harman Over-Ear 2018",
            type = HeadphoneType.OVER_EAR,
            preampDb = -5.8,
            bands = listOf(
                AutoEqBand(1, 28.0, 0.71, 5.5),
                AutoEqBand(0, 1500.0, 1.8, 2.3),
                AutoEqBand(0, 5800.0, 4.0, -4.5),
                AutoEqBand(0, 9200.0, 3.2, -2.8),
                AutoEqBand(2, 10000.0, 0.71, 1.2)
            )
        ),
        // 2. Sennheiser HD 650 / HD 6XX
        AutoEqProfile(
            id = "sennheiser_hd650",
            brand = "Sennheiser",
            model = "HD 650 / HD 6XX",
            targetCurve = "Harman Over-Ear 2018",
            type = HeadphoneType.OVER_EAR,
            preampDb = -6.5,
            bands = listOf(
                AutoEqBand(1, 45.0, 0.71, 6.0),
                AutoEqBand(0, 210.0, 1.2, -2.1),
                AutoEqBand(0, 3200.0, 2.5, -1.8),
                AutoEqBand(0, 5400.0, 3.5, 2.2),
                AutoEqBand(2, 10000.0, 0.71, 3.0)
            )
        ),
        // 3. Sennheiser HD 600
        AutoEqProfile(
            id = "sennheiser_hd600",
            brand = "Sennheiser",
            model = "HD 600",
            targetCurve = "Harman Over-Ear 2018",
            type = HeadphoneType.OVER_EAR,
            preampDb = -6.2,
            bands = listOf(
                AutoEqBand(1, 40.0, 0.71, 6.5),
                AutoEqBand(0, 3500.0, 2.0, -2.5),
                AutoEqBand(0, 7800.0, 3.0, 2.0),
                AutoEqBand(2, 10000.0, 0.71, 2.5)
            )
        ),
        // 4. Sennheiser HD 560S
        AutoEqProfile(
            id = "sennheiser_hd560s",
            brand = "Sennheiser",
            model = "HD 560S",
            targetCurve = "Harman Over-Ear 2018",
            type = HeadphoneType.OVER_EAR,
            preampDb = -4.5,
            bands = listOf(
                AutoEqBand(1, 35.0, 0.71, 4.2),
                AutoEqBand(0, 1300.0, 1.5, -1.5),
                AutoEqBand(0, 4800.0, 4.0, -3.2),
                AutoEqBand(2, 10000.0, 0.71, 1.0)
            )
        ),
        // 5. Hifiman Sundara (2020)
        AutoEqProfile(
            id = "hifiman_sundara",
            brand = "Hifiman",
            model = "Sundara (2020)",
            targetCurve = "Harman Over-Ear 2018",
            type = HeadphoneType.OVER_EAR,
            preampDb = -6.0,
            bands = listOf(
                AutoEqBand(1, 30.0, 0.71, 6.0),
                AutoEqBand(0, 1800.0, 1.4, 2.2),
                AutoEqBand(0, 6000.0, 3.0, -3.0),
                AutoEqBand(0, 9500.0, 2.5, -2.2),
                AutoEqBand(2, 12000.0, 0.71, 1.5)
            )
        ),
        // 6. Hifiman Arya Stealth
        AutoEqProfile(
            id = "hifiman_arya_stealth",
            brand = "Hifiman",
            model = "Arya Stealth",
            targetCurve = "Harman Over-Ear 2018",
            type = HeadphoneType.OVER_EAR,
            preampDb = -5.0,
            bands = listOf(
                AutoEqBand(1, 28.0, 0.71, 4.8),
                AutoEqBand(0, 1600.0, 1.8, 1.8),
                AutoEqBand(0, 3200.0, 3.0, -2.5),
                AutoEqBand(0, 5200.0, 3.5, -3.0),
                AutoEqBand(2, 11000.0, 0.71, 1.2)
            )
        ),
        // 7. Hifiman Edition XS
        AutoEqProfile(
            id = "hifiman_edition_xs",
            brand = "Hifiman",
            model = "Edition XS",
            targetCurve = "Harman Over-Ear 2018",
            type = HeadphoneType.OVER_EAR,
            preampDb = -4.8,
            bands = listOf(
                AutoEqBand(1, 32.0, 0.71, 4.5),
                AutoEqBand(0, 1800.0, 1.5, 2.0),
                AutoEqBand(0, 6200.0, 3.2, -2.8),
                AutoEqBand(2, 10000.0, 0.71, 1.0)
            )
        ),
        // 8. Sony WH-1000XM5
        AutoEqProfile(
            id = "sony_wh1000xm5",
            brand = "Sony",
            model = "WH-1000XM5",
            targetCurve = "Harman Over-Ear 2018",
            type = HeadphoneType.WIRELESS,
            preampDb = -5.5,
            bands = listOf(
                AutoEqBand(0, 180.0, 0.8, -4.5),
                AutoEqBand(0, 1100.0, 1.6, 2.8),
                AutoEqBand(0, 3200.0, 2.2, 3.5),
                AutoEqBand(0, 7500.0, 3.0, -2.0),
                AutoEqBand(2, 10000.0, 0.71, 2.0)
            )
        ),
        // 9. Sony WH-1000XM4
        AutoEqProfile(
            id = "sony_wh1000xm4",
            brand = "Sony",
            model = "WH-1000XM4",
            targetCurve = "Harman Over-Ear 2018",
            type = HeadphoneType.WIRELESS,
            preampDb = -6.2,
            bands = listOf(
                AutoEqBand(0, 160.0, 0.7, -5.5),
                AutoEqBand(0, 950.0, 1.4, 3.0),
                AutoEqBand(0, 2800.0, 2.0, 4.0),
                AutoEqBand(0, 6800.0, 2.8, -3.2),
                AutoEqBand(2, 10000.0, 0.71, 2.5)
            )
        ),
        // 10. Apple AirPods Max
        AutoEqProfile(
            id = "apple_airpods_max",
            brand = "Apple",
            model = "AirPods Max",
            targetCurve = "Harman Over-Ear 2018",
            type = HeadphoneType.WIRELESS,
            preampDb = -3.8,
            bands = listOf(
                AutoEqBand(0, 220.0, 1.2, -2.0),
                AutoEqBand(0, 2200.0, 1.8, 1.5),
                AutoEqBand(0, 4200.0, 3.0, -2.2),
                AutoEqBand(2, 10000.0, 0.71, 2.0)
            )
        ),
        // 11. Apple AirPods Pro 2
        AutoEqProfile(
            id = "apple_airpods_pro_2",
            brand = "Apple",
            model = "AirPods Pro 2",
            targetCurve = "Harman In-Ear 2019",
            type = HeadphoneType.WIRELESS,
            preampDb = -3.2,
            bands = listOf(
                AutoEqBand(1, 40.0, 0.71, 2.5),
                AutoEqBand(0, 2400.0, 2.0, -1.8),
                AutoEqBand(0, 6000.0, 3.0, 2.2),
                AutoEqBand(2, 10000.0, 0.71, 1.5)
            )
        ),
        // 12. Moondrop Blessing 2 / Dusk
        AutoEqProfile(
            id = "moondrop_blessing_2",
            brand = "Moondrop",
            model = "Blessing 2 (Dusk)",
            targetCurve = "Harman In-Ear 2019",
            type = HeadphoneType.IN_EAR,
            preampDb = -4.0,
            bands = listOf(
                AutoEqBand(1, 35.0, 0.71, 3.5),
                AutoEqBand(0, 2800.0, 2.2, -1.5),
                AutoEqBand(0, 6200.0, 3.5, 2.0),
                AutoEqBand(2, 12000.0, 0.71, 2.2)
            )
        ),
        // 13. Moondrop Kato
        AutoEqProfile(
            id = "moondrop_kato",
            brand = "Moondrop",
            model = "Kato",
            targetCurve = "Harman In-Ear 2019",
            type = HeadphoneType.IN_EAR,
            preampDb = -3.5,
            bands = listOf(
                AutoEqBand(1, 30.0, 0.71, 2.8),
                AutoEqBand(0, 3200.0, 2.5, -2.0),
                AutoEqBand(0, 8000.0, 3.0, 1.8),
                AutoEqBand(2, 10000.0, 0.71, 1.5)
            )
        ),
        // 14. Moondrop Aria (Snow Edition)
        AutoEqProfile(
            id = "moondrop_aria",
            brand = "Moondrop",
            model = "Aria / Aria Snow",
            targetCurve = "Harman In-Ear 2019",
            type = HeadphoneType.IN_EAR,
            preampDb = -3.8,
            bands = listOf(
                AutoEqBand(1, 35.0, 0.71, 3.0),
                AutoEqBand(0, 2900.0, 2.0, -1.8),
                AutoEqBand(0, 6500.0, 3.2, 1.5),
                AutoEqBand(2, 11000.0, 0.71, 2.0)
            )
        ),
        // 15. Beyerdynamic DT 770 Pro (80Ω)
        AutoEqProfile(
            id = "beyer_dt770_80",
            brand = "Beyerdynamic",
            model = "DT 770 Pro (80Ω)",
            targetCurve = "Harman Over-Ear 2018",
            type = HeadphoneType.OVER_EAR,
            preampDb = -6.5,
            bands = listOf(
                AutoEqBand(0, 180.0, 1.0, -3.2),
                AutoEqBand(0, 3400.0, 2.2, 2.5),
                AutoEqBand(0, 5900.0, 4.0, -6.5),
                AutoEqBand(0, 8500.0, 3.5, -4.0),
                AutoEqBand(2, 10000.0, 0.71, 1.0)
            )
        ),
        // 16. Beyerdynamic DT 990 Pro (250Ω)
        AutoEqProfile(
            id = "beyer_dt990_250",
            brand = "Beyerdynamic",
            model = "DT 990 Pro (250Ω)",
            targetCurve = "Harman Over-Ear 2018",
            type = HeadphoneType.OVER_EAR,
            preampDb = -7.2,
            bands = listOf(
                AutoEqBand(1, 40.0, 0.71, 6.0),
                AutoEqBand(0, 200.0, 1.2, -2.5),
                AutoEqBand(0, 4200.0, 2.5, 2.0),
                AutoEqBand(0, 5800.0, 4.5, -7.5),
                AutoEqBand(0, 8800.0, 3.0, -5.0)
            )
        ),
        // 17. Beyerdynamic DT 1990 Pro
        AutoEqProfile(
            id = "beyer_dt1990",
            brand = "Beyerdynamic",
            model = "DT 1990 Pro",
            targetCurve = "Harman Over-Ear 2018",
            type = HeadphoneType.OVER_EAR,
            preampDb = -6.0,
            bands = listOf(
                AutoEqBand(1, 35.0, 0.71, 4.5),
                AutoEqBand(0, 1800.0, 1.8, 1.5),
                AutoEqBand(0, 7200.0, 4.0, -6.0),
                AutoEqBand(0, 9500.0, 3.0, -3.0),
                AutoEqBand(2, 12000.0, 0.71, 1.5)
            )
        ),
        // 18. Audio-Technica ATH-M50x
        AutoEqProfile(
            id = "audio_technica_m50x",
            brand = "Audio-Technica",
            model = "ATH-M50x",
            targetCurve = "Harman Over-Ear 2018",
            type = HeadphoneType.OVER_EAR,
            preampDb = -5.5,
            bands = listOf(
                AutoEqBand(0, 160.0, 0.9, -3.5),
                AutoEqBand(0, 400.0, 1.5, 1.8),
                AutoEqBand(0, 3200.0, 2.0, 2.5),
                AutoEqBand(0, 9200.0, 3.5, -4.5),
                AutoEqBand(2, 10000.0, 0.71, 1.5)
            )
        ),
        // 19. Samsung Galaxy Buds2 Pro
        AutoEqProfile(
            id = "samsung_buds2_pro",
            brand = "Samsung",
            model = "Galaxy Buds2 Pro",
            targetCurve = "Harman In-Ear 2019",
            type = HeadphoneType.WIRELESS,
            preampDb = -3.0,
            bands = listOf(
                AutoEqBand(1, 35.0, 0.71, 2.0),
                AutoEqBand(0, 3000.0, 2.5, -1.5),
                AutoEqBand(0, 6500.0, 3.0, 2.0),
                AutoEqBand(2, 11000.0, 0.71, 1.5)
            )
        ),
        // 20. AKG K702
        AutoEqProfile(
            id = "akg_k702",
            brand = "AKG",
            model = "K702",
            targetCurve = "Harman Over-Ear 2018",
            type = HeadphoneType.OVER_EAR,
            preampDb = -6.8,
            bands = listOf(
                AutoEqBand(1, 40.0, 0.71, 7.0),
                AutoEqBand(0, 2200.0, 2.0, -2.5),
                AutoEqBand(0, 5800.0, 3.5, -2.2),
                AutoEqBand(2, 10000.0, 0.71, 2.0)
            )
        ),
        // 21. AKG K371
        AutoEqProfile(
            id = "akg_k371",
            brand = "AKG",
            model = "K371",
            targetCurve = "Harman Over-Ear 2018",
            type = HeadphoneType.OVER_EAR,
            preampDb = -2.8,
            bands = listOf(
                AutoEqBand(0, 180.0, 1.5, -1.8),
                AutoEqBand(0, 3800.0, 2.8, 1.5),
                AutoEqBand(0, 7500.0, 3.2, -1.5),
                AutoEqBand(2, 10000.0, 0.71, 1.2)
            )
        )
    )

    fun findById(id: String): AutoEqProfile? = profiles.firstOrNull { it.id == id }

    fun search(query: String): List<AutoEqProfile> {
        if (query.isBlank()) return profiles
        val q = query.trim().lowercase()
        return profiles.filter {
            it.brand.lowercase().contains(q) ||
            it.model.lowercase().contains(q) ||
            it.displayName.lowercase().contains(q)
        }
    }

    val allBrands: List<String> get() = profiles.map { it.brand }.distinct().sorted()
}
