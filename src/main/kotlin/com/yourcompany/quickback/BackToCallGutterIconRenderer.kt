package com.yourcompany.quickback

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import javax.swing.Icon

class BackToCallGutterIconRenderer(
    private val project: Project,
    private val navigationPoint: NavigationPoint,
    private val currentElement: PsiElement // Element where the icon is shown
) : GutterIconRenderer() {

    override fun getIcon(): Icon = AllIcons.Actions.Back // Using a standard IntelliJ icon

    override fun getTooltipText(): String {
        val sourceFileName = navigationPoint.file.name
        return if (navigationPoint.sourceFunctionDisplayName != null) {
            "Go back to call in ${navigationPoint.sourceFunctionDisplayName} (${sourceFileName})"
        } else {
            "Go back to ${sourceFileName}"
        }
    }

    override fun isNavigateAction(): Boolean = true

    override fun getClickAction(): com.intellij.openapi.actionSystem.AnAction? {
        return object : com.intellij.openapi.actionSystem.AnAction() {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                ApplicationManager.getApplication().invokeLater {
                    OpenFileDescriptor(project, navigationPoint.file, navigationPoint.offset).navigate(true)
                    // Clear the navigation point so the icon disappears after clicking
                    project.service<NavigationHistoryService>().clearLastNavigationSource()
                    // Force refresh of line markers for the current file
                    val currentPsiFile = currentElement.containingFile
                    if (currentPsiFile != null) {
                        DaemonCodeAnalyzer.getInstance(project).restart(currentPsiFile)
                    }
                }
            }
        }
    }

    // For equality checks, important for LineMarkerProvider updates
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BackToCallGutterIconRenderer) return false
        return navigationPoint == other.navigationPoint && project == other.project
    }

    override fun hashCode(): Int {
        return navigationPoint.hashCode() * 31 + project.hashCode()
    }
}
