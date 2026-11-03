package com.avshy.bayitvoice.ha

/** HA calendar versions, e.g. `2025.4.2` or `2025.10.0b1`. */
object HaVersion {

    /** First release whose `intent-end` carries `continue_conversation`. */
    private val CONTINUE_CONVERSATION = 2025 to 4

    fun supportsContinueConversation(version: String?): Boolean {
        val parts = version?.split('.') ?: return true // unknown: assume a current server
        val year = parts.getOrNull(0)?.toIntOrNull() ?: return true
        val month = parts.getOrNull(1)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: return true
        return year > CONTINUE_CONVERSATION.first ||
            (year == CONTINUE_CONVERSATION.first && month >= CONTINUE_CONVERSATION.second)
    }
}
