package com.avshy.bayitvoice.session

/**
 * Hebrew vowel points and cantillation marks. Agents often add them to steer TTS pronunciation;
 * they are noise on screen. Maqaf (U+05BE), paseq (U+05C0), sof pasuq (U+05C3) and nun hafukha
 * (U+05C6) are punctuation and stay.
 */
private val NIQQUD = Regex("[֑-ׇֽֿׁׂׅׄ]")

/** The text as shown to the user: without niqqud. The spoken audio is unaffected. */
fun String.withoutNiqqud(): String = replace(NIQQUD, "")
