package com.avshy.bayitvoice.session

import org.junit.Assert.assertEquals
import org.junit.Test

class NiqqudTest {
    @Test
    fun removesVowelPoints() {
        assertEquals("הדלקתי את האור", "הִדְלַקְתִּי אֶת הָאוֹר".withoutNiqqud())
    }

    @Test
    fun keepsMaqafAndOtherText() {
        assertEquals("בית־ספר, 21°C ok", "בֵּית־סֵפֶר, 21°C ok".withoutNiqqud())
    }
}
