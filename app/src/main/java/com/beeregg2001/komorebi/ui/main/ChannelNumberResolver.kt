package com.beeregg2001.komorebi.ui.main

import com.beeregg2001.komorebi.data.model.Channel

/**
 * Resolves 1-3 digit remote channel input against the available channel list.
 */
object ChannelNumberResolver {
    private val PRIMARY_DIGITS_REGEX = Regex("\\d+")
    private val BRANCH_DIGITS_REGEX = Regex("-(\\d+)")

    private data class ParsedChannel(
        val channel: Channel,
        val primary: String?,
        val primaryInt: Int?,
        val branch: Int,
    )

    fun resolve(
        input: String,
        channels: List<Channel>,
        currentType: String?,
    ): Channel? {
        val digits = input.trim()
        if (digits.isEmpty() || digits.length > RemoteKeyShortcuts.MAX_DIGITS || digits.any { it !in '0'..'9' }) {
            return null
        }

        val candidates = channels
            .asSequence()
            .filterNot { it.id.startsWith("tif:input:") }
            .map { parseChannel(it) }
            .toList()

        if (candidates.isEmpty()) return null

        val comparator = compareBy<ParsedChannel>(
            { typeRank(it.channel, currentType) },
            { it.channel.is_subchannel },
            { it.branch },
            { !it.channel.isDisplay },
            { it.channel.channelNumber },
        )

        val matched = if (digits.length <= 2) {
            val number = digits.toIntOrNull() ?: return null
            if (number == 0) return null
            val byRemocon = candidates.filter { it.channel.remocon_Id == number }
            if (byRemocon.isNotEmpty()) {
                byRemocon
            } else {
                candidates.filter { it.primaryInt == number }
            }
        } else {
            val byChannelNumber = candidates.filter { parsed ->
                parsed.primary != null && parsed.primary.padStart(3, '0') == digits
            }
            if (byChannelNumber.isNotEmpty()) {
                byChannelNumber
            } else {
                val targetServiceId = digits.toLongOrNull()
                if (targetServiceId != null && targetServiceId > 0L) {
                    candidates.filter { it.channel.serviceId == targetServiceId }
                } else {
                    emptyList()
                }
            }
        }

        return matched.minWithOrNull(comparator)?.channel
    }

    private fun parseChannel(channel: Channel): ParsedChannel {
        val raw = channel.channelNumber
        val primary = PRIMARY_DIGITS_REGEX.find(raw)?.value
        val branch = BRANCH_DIGITS_REGEX.find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return ParsedChannel(
            channel = channel,
            primary = primary,
            primaryInt = primary?.toIntOrNull(),
            branch = branch,
        )
    }

    private fun typeRank(channel: Channel, currentType: String?): Int {
        val tifOffset = if (channel.id.startsWith("tif:")) 100 else 0
        val baseRank = when {
            !currentType.isNullOrBlank() && channel.type.equals(currentType, ignoreCase = true) -> 0
            channel.type.equals("GR", ignoreCase = true) -> 1
            channel.type.equals("BS", ignoreCase = true) -> 2
            channel.type.equals("CS", ignoreCase = true) -> 3
            channel.type.equals("SKY", ignoreCase = true) -> 4
            channel.type.equals("BS4K", ignoreCase = true) -> 5
            else -> 6
        }
        return tifOffset + baseRank
    }
}
