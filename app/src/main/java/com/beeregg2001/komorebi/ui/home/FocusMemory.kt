package com.beeregg2001.komorebi.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

@Stable
class FocusMemory {
    var lastFocusedTab by mutableIntStateOf(0)
    var lastFocusedSection by mutableStateOf<String?>(null)
    var lastFocusedItemId by mutableStateOf<String?>(null)

    fun remember(tab: Int, section: String? = null, itemId: String? = null) {
        lastFocusedTab = tab
        lastFocusedSection = section
        lastFocusedItemId = itemId
    }

    fun clear() {
        lastFocusedSection = null
        lastFocusedItemId = null
    }
}

val FocusMemorySaver = Saver<FocusMemory, List<Any?>>(
    save = { listOf(it.lastFocusedTab, it.lastFocusedSection, it.lastFocusedItemId) },
    restore = {
        FocusMemory().apply {
            lastFocusedTab = it[0] as Int
            lastFocusedSection = it[1] as String?
            lastFocusedItemId = it[2] as String?
        }
    }
)

@Composable
fun rememberFocusMemory() = rememberSaveable(saver = FocusMemorySaver) { FocusMemory() }
