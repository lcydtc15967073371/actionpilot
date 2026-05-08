package com.operit.actionpilot.model

/**
 * Shared state for which apps the user wants to record.
 */
object AppSelection {
    val selectedPackages = mutableSetOf<String>()

    fun isSelected(pkg: String): Boolean = selectedPackages.contains(pkg)

    fun toggle(pkg: String, selected: Boolean) {
        if (selected) selectedPackages.add(pkg)
        else selectedPackages.remove(pkg)
    }

    fun selectAll() {
        // filled externally
    }

    fun clear() {
        selectedPackages.clear()
    }

    /** If empty, record all apps */
    fun isFiltering(): Boolean = selectedPackages.isNotEmpty()
}
