package com.yourcompany.quickback

import com.intellij.openapi.vfs.VirtualFile

data class NavigationPoint(
    val file: VirtualFile,
    val offset: Int,
    val sourceFunctionDisplayName: String? // e.g., "function_A"
)

interface NavigationHistoryService {
    fun setLastNavigationSource(point: NavigationPoint?)
    fun getLastNavigationSource(): NavigationPoint?
    fun clearLastNavigationSource() {
        setLastNavigationSource(null)
    }
}
