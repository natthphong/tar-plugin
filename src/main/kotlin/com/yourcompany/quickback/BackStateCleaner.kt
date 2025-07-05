package com.yourcompany.quickback

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.diagnostic.Logger

@Service(Service.Level.PROJECT)
class BackStateCleaner(private val project: Project) :
    FileEditorManagerListener, DocumentListener, Disposable {

    companion object {
        private val LOG = Logger.getInstance(BackStateCleaner::class.java)
    }

    // Stores the file where the "back" icon is currently presumed to be.
    // This is set by the LineMarkerProvider when it successfully adds a marker.
    @Volatile
    var currentTargetFileWithBackIcon: VirtualFile? = null

    init {
        val messageBusConnection = project.messageBus.connect(this)
        messageBusConnection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER_TOPIC, this)
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(this, this)
        LOG.info("BackStateCleaner initialized and listeners registered for project ${project.name}.")
    }

    // --- DocumentListener ---
    override fun beforeDocumentChange(event: DocumentEvent) {
        // Not used, but required by interface if not using BulkAwareDocumentListener
    }

    override fun documentChanged(event: DocumentEvent) {
        val historyService = project.service<NavigationHistoryService>()
        if (historyService.getLastNavigationSource() == null) return // No active "back" state

        val modifiedDoc = event.document
        val modifiedVirtualFile = FileDocumentManager.getInstance().getFile(modifiedDoc)

        // If the document that changed is the one where we think the icon is, clear state.
        if (modifiedVirtualFile != null && modifiedVirtualFile == currentTargetFileWithBackIcon) {
            LOG.debug("Document changed: ${modifiedVirtualFile.name} (where icon was). Clearing navigation history.")
            clearHistoryAndRefresh(historyService, modifiedVirtualFile, "document modification in ${modifiedVirtualFile.name}")
        }
    }

    // --- FileEditorManagerListener ---
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) { /* No action needed */ }

    override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
        val historyService = project.service<NavigationHistoryService>()
        if (historyService.getLastNavigationSource() == null) return

        // If the file that was closed is where the icon was, clear the state.
        if (file == currentTargetFileWithBackIcon) {
            LOG.debug("File closed: ${file.name} (where icon was). Clearing navigation history.")
            // No need to refresh markers for a closed file. Just clear the state.
            historyService.clearLastNavigationSource()
            currentTargetFileWithBackIcon = null // Reset our tracked target
        }
    }

    override fun selectionChanged(event: FileEditorManagerEvent) {
        val historyService = project.service<NavigationHistoryService>()
        if (historyService.getLastNavigationSource() == null) return

        val newFile = event.newFile
        val oldFile = event.oldFile // File that lost focus, potentially had the icon

        // If selection changed to a *different* file than where the icon was, clear.
        if (newFile != null && oldFile != null && newFile != oldFile) {
            if (oldFile == currentTargetFileWithBackIcon) {
                LOG.debug("Selection changed from ${oldFile.name} (where icon was) to ${newFile.name}. Clearing history.")
                clearHistoryAndRefresh(historyService, oldFile, "selection changed from ${oldFile.name} to ${newFile.name}")
            }
        } else if (newFile == null && oldFile != null) { // Editor focus lost from oldFile
             if (oldFile == currentTargetFileWithBackIcon) {
                LOG.debug("Selection changed from ${oldFile.name} (where icon was) to null. Clearing history.")
                clearHistoryAndRefresh(historyService, oldFile, "selection changed from ${oldFile.name} to null")
             }
        }
    }

    private fun clearHistoryAndRefresh(
        historyService: NavigationHistoryService,
        fileToRefreshMarkersIn: VirtualFile?,
        reason: String
    ) {
        LOG.info("Clearing navigation history due to: $reason. Target file was: ${currentTargetFileWithBackIcon?.name}")
        val previousTarget = currentTargetFileWithBackIcon
        historyService.clearLastNavigationSource()
        currentTargetFileWithBackIcon = null // Reset our tracked target

        val fileToRefresh = fileToRefreshMarkersIn ?: previousTarget

        if (fileToRefresh != null && project.isOpen && !project.isDisposed) {
            ApplicationManager.getApplication().invokeLater {
                 if (!project.isDisposed && fileToRefresh.isValid) { // Check file validity
                    val psiFile = PsiDocumentManager.getInstance(project).findFile(fileToRefresh)
                    if (psiFile != null) {
                        LOG.debug("Requesting daemon restart for ${fileToRefresh.name} to remove icon.")
                        DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
                    } else {
                        LOG.warn("Could not find PsiFile for ${fileToRefresh.name} to restart daemon (it might be closed or invalid).")
                    }
                 }
            }
        } else {
             LOG.debug("No specific file to refresh or project/file is not in a state to be refreshed.")
        }
    }

    override fun dispose() {
        LOG.info("BackStateCleaner disposed for project ${project.name}.")
    }
}
