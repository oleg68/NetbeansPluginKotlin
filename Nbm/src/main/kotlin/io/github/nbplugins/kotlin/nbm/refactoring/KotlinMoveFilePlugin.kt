/*******************************************************************************
 * Copyright 2026 nbplugins contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *******************************************************************************/
package io.github.nbplugins.kotlin.nbm.refactoring

import io.github.nbplugins.kotlin.nbm.navigation.KotlinFindUsagesResultElement
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.refactoring.KaMoveDeclarationComputer
import io.github.nbplugins.kotlin.refactoring.KaMoveFileComputer
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.modules.csl.api.OffsetRange
import org.netbeans.modules.refactoring.api.Problem
import org.netbeans.modules.refactoring.spi.ProgressProviderAdapter
import org.netbeans.modules.refactoring.spi.RefactoringElementsBag
import org.netbeans.modules.refactoring.spi.RefactoringPlugin
import org.netbeans.modules.refactoring.spi.SimpleRefactoringElementImplementation
import org.openide.filesystems.FileObject
import org.openide.filesystems.FileUtil
import org.openide.text.CloneableEditorSupport
import org.openide.text.PositionBounds
import org.openide.util.Lookup
import org.openide.util.lookup.Lookups
import javax.swing.text.Position.Bias

/**
 * Bridges NetBeans Move Kotlin File UI to the standalone K2 Move File semantic adapter.
 *
 * NetBeans owns destination creation, physical movement, document persistence, rollback, and undo.
 * The ported K2 handler supplies eligible package rewriting, Kotlin conflict detection, and
 * retargeting of supported external Kotlin references.
 *
 * @param refactoring carrier containing the destination selected in the Move File UI
 */
class KotlinMoveFilePlugin(
    private val refactoring: KotlinMoveFileRefactoring,
) : ProgressProviderAdapter(), RefactoringPlugin {

    /** @return no preliminary problem; validation happens while preparing the physical destination. */
    override fun preCheck(): Problem? = null

    /** @return no fast problem; the UI validates the package before this point. */
    override fun fastCheckParameters(): Problem? = null

    /** @return no extra parameter problem. */
    override fun checkParameters(): Problem? = null

    /** Cancels no external work because all Move File work runs in the refactoring lifecycle. */
    override fun cancelRequest() = Unit

    /**
     * Adds one source preview element and one atomic apply element for the current Kotlin file.
     *
     * @param bag NetBeans bag populated with preview and mutation elements
     * @return a fatal problem when the selected source cannot be analyzed, otherwise `null`
     */
    override fun prepare(bag: RefactoringElementsBag): Problem? {
        val source = ProjectUtils.getFileObjectForDocument(refactoring.document) ?: return null
        val project = ProjectUtils.getKotlinProjectForFileObject(source) ?: return null
        val file = KotlinAnalysisAPISession.getSession(project).getKtFileForPath(source.path) ?: return null
        return when (val outcome = KaMoveFileComputer(file).compute()) {
            is KaMoveFileComputer.Outcome.NotApplicable -> Problem(true, "The selected Kotlin file has no physical path.")
            is KaMoveFileComputer.Outcome.Error -> Problem(true, outcome.error.message ?: "Move Kotlin File analysis failed.")
            is KaMoveFileComputer.Outcome.Ready -> {
                bag.add(
                    refactoring,
                    KotlinFindUsagesResultElement(OffsetRange(0, file.textLength), source),
                )
                bag.add(refactoring, KotlinMoveFileApplyElement(source, project, refactoring))
                null
            }
        }
    }
}

/**
 * Performs one atomic Kotlin file move and retains the transaction for Undo Last Refactoring.
 *
 * @param sourceFile source file selected by the editor action
 * @param project NetBeans project owning the source file
 * @param refactoring destination and search parameters
 */
class KotlinMoveFileApplyElement(
    private val sourceFile: FileObject,
    private val project: org.netbeans.api.project.Project,
    private val refactoring: KotlinMoveFileRefactoring,
) : SimpleRefactoringElementImplementation() {

    /** Successful transaction retained so [undoChange] can restore original paths and text. */
    private var transaction: KotlinRefactoringTransaction? = null

    /** @return user-visible preview text. */
    override fun getText(): String = "Move Kotlin file"

    /** @return user-visible preview text. */
    override fun getDisplayText(): String = getText()

    /** @return source file lookup. */
    override fun getLookup(): Lookup = Lookups.fixed(sourceFile)

    /** @return original source file for NetBeans preview placement. */
    override fun getParentFile(): FileObject = sourceFile

    /** @return source position bounds, when the editor support is available. */
    override fun getPosition(): PositionBounds? = try {
        val support = org.openide.loaders.DataObject.find(sourceFile)
            .lookup.lookup(CloneableEditorSupport::class.java) ?: return null
        PositionBounds(
            support.createPositionRef(0, Bias.Forward),
            support.createPositionRef(0, Bias.Backward),
        )
    } catch (_: Exception) {
        null
    }

    /**
     * Runs K2 semantics first, then atomically moves the physical file and persists every changed
     * Kotlin document. Any failure rolls paths, documents, owned destination folders, and created
     * files back to their original state.
     *
     * @return no value; failures are logged and the transaction is rolled back
     */
    override fun performChange() {
        var pending: KotlinRefactoringTransaction? = null
        runCatching {
            val activeSource = ProjectUtils.getFileObjectForDocument(refactoring.document) ?: sourceFile
            val sourceProject = ProjectUtils.getKotlinProjectForFileObject(activeSource) ?: project
            val packageTarget = KotlinPackageTarget(sourceProject, activeSource)
            val targetRoot = packageTarget.roots.firstOrNull { it.path == refactoring.targetRootPath }
                ?: packageTarget.roots.firstOrNull { it.path == packageTarget.defaultRootPath }
                ?: error("Move Kotlin File could not find a destination source root.")
            val targetPackage = refactoring.targetPackage.trim()
            check(packageTarget.isValidPackage(targetPackage)) { "Target package is invalid: $targetPackage" }

            val current = KotlinRefactoringTransaction()
            pending = current
            val targetFolder = createTargetFolder(current, targetRoot.folder, targetPackage)
            check(targetFolder != activeSource.parent) { "Kotlin file is already in the selected destination folder." }
            check(targetFolder.getFileObject(activeSource.name, activeSource.ext) == null) {
                "Target already contains ${activeSource.nameExt}."
            }

            val session = KotlinAnalysisAPISession.getSession(sourceProject)
            val sourceKtFile = session.getKtFileForPath(activeSource.path)
                ?: error("Move Kotlin File could not resolve writable source PSI.")
            // The session's disk-backed PSI is required only for target metadata/conflicts. Its
            // VFS cannot create or move files; NetBeans performs that separately in the transaction.
            val targetDirectory = KaMoveDeclarationComputer.resolveDirectory(sourceKtFile.project, targetFolder.path)
                ?: error("Move Kotlin File could not resolve destination PSI directory.")
            val outcome = KaMoveFileComputer(sourceKtFile).apply(
                files = listOf(sourceKtFile),
                targetDirectory = targetDirectory,
                targetPackage = FqName(targetPackage),
                updateReferences = refactoring.updateReferences,
            )
            when (outcome) {
                is KaMoveFileComputer.ApplyOutcome.Conflicts -> {
                    throw IllegalStateException(outcome.messages.joinToString("\n"))
                }
                is KaMoveFileComputer.ApplyOutcome.Error -> throw outcome.error
                is KaMoveFileComputer.ApplyOutcome.Success -> {
                    current.moveFile(activeSource, targetFolder)
                    outcome.changedFiles.forEach { (path, text) ->
                        val file = if (path == activeSource.path) {
                            activeSource
                        } else {
                            FileUtil.toFileObject(FileUtil.normalizeFile(java.io.File(path)))
                                ?: error("Move Kotlin File could not resolve changed file $path.")
                        }
                        val originalText = outcome.originalTexts[path]
                        KotlinLogger.INSTANCE.logInfo(
                            "KotlinMoveFileApplyElement.performChange: staging path=$path, " +
                                "original=${describeText(originalText)}, final=${describeText(text)}"
                        )
                        current.captureExisting(file, originalText)
                        current.stageText(file, text)
                    }
                    current.commit()
                    transaction = current
                    KotlinLogger.INSTANCE.logInfo("KotlinMoveFileApplyElement.performChange: transaction committed")
                    pending = null
                }
            }
        }.onFailure { error ->
            KotlinLogger.INSTANCE.logException("KotlinMoveFileApplyElement.performChange failed", error)
        }
        runCatching { pending?.rollback() }
            .onFailure { error -> KotlinLogger.INSTANCE.logException("KotlinMoveFileApplyElement rollback failed", error) }
        KotlinAnalysisAPISession.invalidate(project)
    }

    /** Restores the original source path and text through the transaction retained after commit. */
    override fun undoChange() {
        runCatching {
            val current = transaction
            if (current == null) {
                KotlinLogger.INSTANCE.logWarning("KotlinMoveFileApplyElement.undoChange: no retained transaction")
            } else {
                KotlinLogger.INSTANCE.logInfo("KotlinMoveFileApplyElement.undoChange: restoring retained transaction")
                current.undo()
                KotlinLogger.INSTANCE.logInfo("KotlinMoveFileApplyElement.undoChange: transaction restored")
                transaction = null
            }
            KotlinAnalysisAPISession.invalidate(project)
        }.onFailure { error ->
            KotlinLogger.INSTANCE.logException("KotlinMoveFileApplyElement.undoChange failed", error)
        }
    }

    /** Produces bounded, one-line diagnostic text without logging full source contents. */
    private fun describeText(text: String?): String =
        if (text == null) "missing" else "length=${text.length}, head=${text.take(160).replace("\n", "\\n")}"

    /** Creates only destination package folders that this transaction can safely delete on undo. */
    private fun createTargetFolder(
        transaction: KotlinRefactoringTransaction,
        root: FileObject,
        packageName: String,
    ): FileObject = packageName.split('.').filter(String::isNotEmpty).fold(root) { parent, segment ->
        transaction.createFolder(parent, segment)
    }
}
