package com.yourcompany.quickback

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class NavigationHistoryServiceImpl(private val project: Project) : NavigationHistoryService {
    @Volatile
    private var lastPoint: NavigationPoint? = null

    override fun setLastNavigationSource(point: NavigationPoint?) {
        lastPoint = point
    }

    override fun getLastNavigationSource(): NavigationPoint? {
        return lastPoint
    }
}
