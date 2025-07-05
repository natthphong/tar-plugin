package com.yourcompany.quickback

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile

class QuickBackLineMarkerProvider : LineMarkerProvider {

    companion object {
        private val LOG = Logger.getInstance(QuickBackLineMarkerProvider::class.java)
    }

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        val project = element.project
        val historyService = project.service<NavigationHistoryService>()
        val backStateCleaner = project.service<BackStateCleaner>()
        val currentFileOfElement = element.containingFile?.virtualFile

        val lastNavSource = historyService.getLastNavigationSource()
        if (lastNavSource == null) {
            // If there's no source, but this file was marked as having the icon, clear that mark.
            if (currentFileOfElement != null && currentFileOfElement == backStateCleaner.currentTargetFileWithBackIcon) {
                LOG.debug("No lastNavSource, clearing currentTargetFileWithBackIcon for ${currentFileOfElement.name}")
                backStateCleaner.currentTargetFileWithBackIcon = null
            }
            return null
        }

        // We want to show the icon on the element we just navigated TO.
        // This element should be a named declaration (like a function or class name).
        // The `element` parameter is iterated over by IntelliJ for all PSI elements in view.
        // We need to identify if `element` is the *target* of our last navigation.

        val currentEditor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        val currentPsiFile = PsiDocumentManager.getInstance(project).getPsiFile(currentEditor.document) ?: return null
        val currentVirtualFile = currentPsiFile.virtualFile ?: return null

        // Don't show icon if we are still in the source file of navigation, unless it's a different offset (self-navigation)
        if (currentVirtualFile == lastNavSource.file && element.textRange.contains(lastNavSource.offset)) {
            // This condition means we are likely still at the source of the navigation, or navigated to self.
            // Only clear if we are AT the original source element to prevent premature clearing.
            // A better check would be if the current editor's caret is at lastNavSource.offset.
            // For now, if the "destination" is the exact same file and contains the original offset, don't show.
            // This avoids showing "back" to the same spot if navigation didn't really go anywhere new.
            // However, if we navigated from functionA to functionA (e.g. recursive call definition), this would hide it.
            // Let's refine: show if current element is NOT where we came from.
             if (currentVirtualFile == lastNavSource.file && currentEditor.caretModel.offset == lastNavSource.offset) {
                 LOG.debug("Not showing marker: current location is the same as source navigation point.")
                 return null
             }
        }

        // Show the icon on the named identifier of a declaration (e.g., function name, class name).
        // The `element` passed here is often the identifier itself.
        if (element is PsiNameIdentifierOwner && element.nameIdentifier == element) {
            // Check if this element's file and rough location matches the *current* editor focus,
            // not the `lastNavSource` file. We are marking the DESTINATION.
            if (element.containingFile.virtualFile == currentVirtualFile) {
                 // Heuristic: If the current caret is inside this element, it's a good candidate.
                 // This helps ensure we put the marker on the item that was actually navigated to.
                if (element.textRange.contains(currentEditor.caretModel.offset) ||
                    element.parent.textRange.contains(currentEditor.caretModel.offset)) { // check parent for broader scope

                    LOG.debug("Showing marker for element: ${element.text} in file ${currentVirtualFile.name}")
                    project.service<BackStateCleaner>().currentTargetFileWithBackIcon = currentVirtualFile

                    return LineMarkerInfo(
                        element, // Element to attach to (the PsiNameIdentifierOwner itself)
                        element.textRange, // Text range to highlight (usually the element's range)
                        BackToCallGutterIconRenderer(project, lastNavSource, element).icon, // Icon is taken from renderer
                        null, // Tooltip provider (renderer handles it)
                        { _, elt -> // Navigation handler (renderer handles it)
                            val renderer = BackToCallGutterIconRenderer(project, lastNavSource, elt)
                            // Clear target file before performing action, as action will clear history
                            project.service<BackStateCleaner>().currentTargetFileWithBackIcon = null
                            renderer.getClickAction()?.actionPerformed(
                                com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext(
                                    com.intellij.openapi.actionSystem.ActionPlaces.UNKNOWN, null
                                ) { key -> FileEditorManager.getInstance(project).selectedTextEditor?.dataContext?.getData(key) }
                            )
                        },
                        GutterIconRenderer.Alignment.LEFT // Alignment in the gutter
                    ).apply {
                        this.lineMarkerTooltip = BackToCallGutterIconRenderer(project, lastNavSource, element).tooltipText
                    }
                } else {
                    LOG.trace("Skipping marker: element ${element.text} not at current caret position ${currentEditor.caretModel.offset}")
                     // If this element is NOT where the caret is, but it IS in the file currently marked as target,
                     // and this LineMarkerProvider is being called (e.g. due to a different element check or refresh),
                     // we should NOT clear currentTargetFileWithBackIcon here.
                     // It should only be cleared by BackStateCleaner itself or when the icon is clicked.
                }
            }
        }
        return null
    }
}
