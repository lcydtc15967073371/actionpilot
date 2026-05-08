package com.operit.actionpilot.model

import androidx.compose.runtime.mutableStateOf

/**
 * Shared state for which apps the user wants to record.
 * Uses Compose mutableStateOf to trigger UI recomposition on changes.
 */
object AppSelection {
    private val _selectedPackages = mutableStateOf(setOf<String>())
    val selectedPackages: Set<String> get() = _selectedPackages.value

    fun isSelected(pkg: String): Boolean = pkg in selectedPackages

    fun toggle(pkg: String, selected: Boolean) {
        _selectedPackages.value = if (selected)
            selectedPackages + pkg
        else
            selectedPackages - pkg
    }

    fun selectAll(allPackages: List<String>) {
        _selectedPackages.value = allPackages.toSet()
    }

    fun clear() {
        _selectedPackages.value = emptySet()
    }

    fun isFiltering(): Boolean = selectedPackages.isNotEmpty()
}
