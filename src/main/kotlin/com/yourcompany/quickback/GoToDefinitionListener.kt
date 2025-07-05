package com.yourcompany.quickback

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.openapi.diagnostic.Logger // Added for logging
import com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtClassOrObject


class GoToDefinitionListener : AnActionListener {
    companion object {
        private val LOG = Logger.getInstance(GoToDefinitionListener::class.java) // Added Logger instance
        private val RELEVANT_ACTION_IDS = setOf(
            "GotoDeclaration", // Standard go to declaration
            "GotoDeclarationOnly",
            "GotoTypeDeclaration",
            "GotoImplementation",
            // Potentially others like "QuickDefinition" if we want to handle previews,
            // but that might make the "back" button appear too often.
        )
    }

    override fun beforeActionPerformed(action: AnAction, dataContext: DataContext, event: AnActionEvent) {
        val project = event.project ?: return
        val actionId = ActionManager.getInstance().getId(action)

        if (actionId in RELEVANT_ACTION_IDS) {
            LOG.debug("Action performed: $actionId") // Log action
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
            val currentFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)?.virtualFile ?: return

            // Ignore navigation from library sources or SDKs for now, focus on project files
            // This check can be refined or made configurable
            if (ProjectRootManager.getInstance(project).fileIndex.isInLibrarySource(currentFile) ||
                ProjectRootManager.getInstance(project).fileIndex.isInLibraryClasses(currentFile)) {
                LOG.debug("Skipping navigation from library file: ${currentFile.path}")
                // Clear any previous point so the back button doesn't show up if we jump from lib to project code
                project.service<NavigationHistoryService>().clearLastNavigationSource()
                return
            }

            val offset = editor.caretModel.offset
            val sourceFunctionName = findEnclosingFunctionName(project, editor, offset)

            LOG.debug("Storing navigation source: ${currentFile.name}, offset $offset, function: $sourceFunctionName")
            val navigationPoint = NavigationPoint(currentFile, offset, sourceFunctionName)
            project.service<NavigationHistoryService>().setLastNavigationSource(navigationPoint)
        }
    }

    private fun findEnclosingFunctionName(project: Project, editor: Editor, offset: Int): String? {
        var name: String? = null
        ApplicationManager.getApplication().runReadAction {
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return@runReadAction
            var currentElement = psiFile.findElementAt(offset)
            while (currentElement != null) {
                if (currentElement is PsiMethod) {
                    name = currentElement.name
                    break
                }
                if (currentElement is KtNamedFunction) {
                    name = currentElement.name
                    break
                }
                // As a fallback, if we are inside a class, use class name.
                // This could be expanded. The original request focused on "function_A".
                if (currentElement is com.intellij.psi.PsiClass && name == null) {
                     name = currentElement.name
                }
                 if (currentElement is org.jetbrains.kotlin.psi.KtClassOrObject && name == null) {
                    name = currentElement.name
                }
                currentElement = currentElement.parent
            }
        }
        return name
    }

    override fun afterActionPerformed(action: AnAction, dataContext: DataContext, event: AnActionEvent) {
        // Potentially, if the navigation failed or didn't change the location,
        // we might want to clear the lastNavigationSource here.
        // For now, we rely on the LineMarkerProvider to only show the icon if navigation actually happened
        // to a *different* location.
        val project = event.project
        val actionId = ActionManager.getInstance().getId(action)
        if (project != null && actionId in RELEVANT_ACTION_IDS) {
            LOG.debug("After action: $actionId. Current editor will be checked by LineMarkerProvider.")
            // Force a refresh of code insight features, including line markers
            // This helps if the navigation lands in the same editor but different location.
             ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    PsiDocumentManager.getInstance(project).commitAllDocuments() // Ensure PSI is up-to-date
                    val currentEditor = FileEditorManager.getInstance(project).selectedTextEditor
                    if (currentEditor != null) {
                        // This is a bit of a heavy hammer, but ensures line markers are re-evaluated.
                        // com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
                        // For now, let's rely on the focus change or FileEditorManagerListener to trigger updates.
                        // If issues arise, we can add more explicit refresh calls.
                    }
                }
            }
        }
    }
}
