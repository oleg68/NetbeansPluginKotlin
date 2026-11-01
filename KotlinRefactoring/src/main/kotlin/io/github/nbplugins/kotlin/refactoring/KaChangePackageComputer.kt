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
package io.github.nbplugins.kotlin.refactoring

import com.intellij.refactoring.util.MoveRenameUsageInfo
import com.intellij.usageView.UsageInfo
import org.jetbrains.kotlin.idea.k2.refactoring.move.KotlinMoveUsageSearchService
import org.jetbrains.kotlin.idea.k2.refactoring.move.MoveImport
import org.jetbrains.kotlin.idea.k2.refactoring.move.descriptor.K2ChangePackageDescriptor
import org.jetbrains.kotlin.idea.k2.refactoring.move.processor.K2ChangePackageRefactoringProcessor
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/**
 * Applies IDEA K2 Change Package semantics without its IDE-specific processor lifecycle.
 *
 * NetBeans supplies writable session PSI and persists the returned text atomically. The computer
 * retains the upstream algorithm's usage discovery, conflict pass, and package rewrite, then
 * retargets supported Kotlin references by rewriting captured source text. It deliberately leaves
 * filesystem mutations to no one: changing a package never changes a physical path.
 *
 * Source text is used for reference retargeting because mutating a package directive invalidates
 * every standalone PSI smart pointer, including those held by K2 [UsageInfo] objects. The IDEA
 * implementation survives this only because its documents are synchronised between the phases.
 *
 * @param ktFile representative writable K2-session source file
 */
class KaChangePackageComputer(
    private val ktFile: KtFile,
) {
    private companion object {
        /** Matches a file-level Kotlin package directive in source text. */
        val PACKAGE_DIRECTIVE = Regex("(?m)^package\\s+[^\\s;]+")

        /** Matches a non-aliased Kotlin import directive in source text. */
        val IMPORT_DIRECTIVE = Regex("(?m)^import\\s+([A-Za-z_][A-Za-z0-9_.]*)\\s*$")
    }

    /** Describes the result of applying package changes to one or more Kotlin source files. */
    sealed class ApplyOutcome {
        /** K2 found blocking semantic conflicts and did not mutate any PSI. */
        data class Conflicts(val messages: List<String>) : ApplyOutcome()

        /** K2 produced final texts and pre-mutation snapshots for every changed participant. */
        data class Success(
            val changedFiles: Map<String, String>,
            val originalTexts: Map<String, String>,
        ) : ApplyOutcome()

        /** An unexpected K2 or PSI failure prevented the operation. */
        data class Error(val error: Throwable) : ApplyOutcome()
    }

    /**
     * Changes every source in [targets] to its requested package and retargets supported usages.
     *
     * @param targets writable source PSI mapped to their final declared packages
     * @param updateReferences whether supported external Kotlin references and direct imports update
     * @return conflicts without mutation, changed texts with original snapshots, or an error
     */
    fun apply(targets: Map<KtFile, FqName>, updateReferences: Boolean): ApplyOutcome {
        return try {
            require(targets.isNotEmpty()) { "Change Package requires at least one Kotlin file." }
            val files = targets.keys.toList()
            require(files.all { it.virtualFile?.path != null }) { "Change Package requires physical Kotlin files." }

            val processors = targets.entries.groupBy({ it.value }, { it.key }).map { (targetPackage, groupedFiles) ->
                K2ChangePackageRefactoringProcessor(
                    K2ChangePackageDescriptor(
                        project = ktFile.project,
                        files = groupedFiles.toSet(),
                        target = targetPackage,
                        searchForText = false,
                        searchInComments = false,
                    ),
                )
            }
            // Use the copied upstream Change Package processor rather than the F4.1 Move File helper:
            // its findUsages() also reports whole-file internal references, so a same-package caller
            // without any import is still known and gets an explicit import once the package changes.
            val usages = if (updateReferences) processors.flatMap { it.findUsages().asIterable() }.distinct() else emptyList()
            val conflicts = processors.flatMap { processor ->
                processor.findConflicts(usages.filterIsInstance<MoveRenameUsageInfo>()).values()
            }
            if (conflicts.isNotEmpty()) return ApplyOutcome.Conflicts(conflicts.distinct())

            val movedDeclarations = files.flatMap { file ->
                file.declarations.mapNotNull { declaration ->
                    (declaration as? KtNamedDeclaration)?.name?.let { name ->
                        file.packageFqName.child(Name.identifier(name)) to
                            targets.getValue(file).child(Name.identifier(name))
                    }
                }
            }.toMap()
            val samePackageImportUpdates = if (updateReferences) {
                findSamePackageImportUpdates(files, targets)
            } else {
                emptyMap()
            }
            val importOnlyUsageFiles = if (updateReferences) {
                KotlinMoveUsageSearchService.getInstance()
                    ?.findImportingFiles(
                        ktFile.project,
                        movedDeclarations.mapKeys { (oldFqName, _) ->
                            MoveImport(oldFqName.parent(), oldFqName.shortName().asString())
                        }.mapValues { (_, newFqName) -> newFqName.parent() },
                    )
                    .orEmpty()
            } else {
                emptyList()
            }
            val participants = (files + filesWithUsages(usages) + importOnlyUsageFiles + samePackageImportUpdates.keys)
                .distinct()
            // Snapshot before any mutation: updatePackageDirective invalidates standalone PSI.
            val originalTexts = participants.mapNotNull { file ->
                file.virtualFile?.path?.let { path -> path to file.text }
            }.toMap()

            // Everything needed from PSI is captured above. From here on only text is touched,
            // because a package-directive replacement invalidates standalone PSI stubs and every
            // K2 UsageInfo smart pointer the upstream retargeter would dereference.
            processors.forEach { processor -> processor.updatePackages() }

            val changedFiles = linkedMapOf<String, String>()
            participants.forEach { file ->
                val changedText = rewriteMovedReferences(
                    file.text,
                    movedDeclarations,
                    samePackageImportUpdates[file].orEmpty(),
                )
                file.virtualFile?.path?.let { changedFiles[it] = changedText }
            }
            ApplyOutcome.Success(changedFiles, originalTexts)
        } catch (error: Throwable) {
            ApplyOutcome.Error(error)
        }
    }

    /**
     * Finds old-package simple references that will lose their implicit package visibility.
     *
     * Text-based retargeting can rewrite an existing import but cannot invent one, so discover the
     * affected callers before directives change and later add only the imports they need.
     */
    private fun findSamePackageImportUpdates(
        movedFiles: Collection<KtFile>,
        targets: Map<KtFile, FqName>,
    ): Map<KtFile, Set<FqName>> {
        val movedDeclarations = movedFiles.flatMap { file ->
            file.declarations.filterIsInstance<KtNamedDeclaration>().mapNotNull { declaration ->
                declaration.name?.let { name -> declaration to targets.getValue(file).child(Name.identifier(name)) }
            }
        }.toMap()
        if (movedDeclarations.isEmpty()) return emptyMap()
        val session = KotlinMoveUsageSearchService.getInstance() ?: return emptyMap()
        val updates = linkedMapOf<KtFile, MutableSet<FqName>>()
        movedDeclarations.forEach { (declaration, targetFqName) ->
            session.findUsages(declaration).forEach { reference ->
                val file = reference.element?.containingFile as? KtFile ?: return@forEach
                val originalPackage = declaration.containingKtFile.packageFqName
                val finalPackage = targets[file]
                if (file.packageFqName != originalPackage || finalPackage == targetFqName.parent()) return@forEach
                updates.getOrPut(file, ::linkedSetOf) += targetFqName
            }
        }
        return updates
    }

    /**
     * Adds imports for former same-package declarations that no longer resolve implicitly.
     *
     * The header is rebuilt as text rather than through PSI: the import list is invalid after the
     * package directive changed, and the standalone parser drops inserted whitespace nodes.
     *
     * @return final source text with a canonical package/import boundary
     */
    private fun addSamePackageImports(usageText: String, imports: Set<FqName>): String {
        if (imports.isEmpty()) return usageText
        val packageMatch = PACKAGE_DIRECTIVE.find(usageText) ?: return usageText
        val existingFqNames = IMPORT_DIRECTIVE.findAll(usageText)
            .map { match -> FqName(match.groupValues[1]) }
            .toSet()
        val missingImports = imports.filterNot { it in existingFqNames }
            .map { imported -> "import ${imported.asString()}" }
        if (missingImports.isEmpty()) return usageText
        val firstImport = IMPORT_DIRECTIVE.find(usageText)
        return if (firstImport == null) {
            usageText.substring(0, packageMatch.range.last + 1) +
                "\n\n${missingImports.joinToString(separator = "\n")}" +
                usageText.substring(packageMatch.range.last + 1)
        } else {
            usageText.substring(0, firstImport.range.first) +
                "${missingImports.joinToString(separator = "\n")}\n" +
                usageText.substring(firstImport.range.first)
        }
    }

    /** Captures usage files before K2 reference rebinding can invalidate individual usage elements. */
    private fun filesWithUsages(usages: Collection<UsageInfo>): List<KtFile> =
        usages.mapNotNull { usage -> usage.element?.containingFile as? KtFile }.distinct()

    /**
     * Rewrites imports and fully qualified Kotlin references without touching invalid standalone PSI.
     *
     * [K2ChangePackageRefactoringProcessor.updatePackages] invalidates every standalone PSI smart
     * pointer. Source text has already been captured in [originalTexts] at that point, so update
     * only FQN token sequences and import directives here; ordinary simple references continue to
     * resolve through their rewritten imports.
     *
     * @param usageText source text read after the package directive changed
     * @param movedDeclarations old declaration FQNs mapped to their new FQNs
     * @param samePackageImports explicit imports needed by callers that formerly shared a package
     * @return source text that preserves supported Kotlin bindings after the package change
     */
    private fun rewriteMovedReferences(
        usageText: String,
        movedDeclarations: Map<FqName, FqName>,
        samePackageImports: Set<FqName>,
    ): String {
        val retargetedText = if (movedDeclarations.isEmpty()) {
            usageText
        } else {
            usageText.replaceFqNames(movedDeclarations)
        }
        return addSamePackageImports(retargetedText, samePackageImports)
    }

    /**
     * Replaces the longest matching declaration FQN at each position, outside comments and strings.
     *
     * Longest match wins so that a qualified access such as `a.b.C.member` keeps its suffix even
     * when both `a.b.C` and a shorter prefix are moved declarations.
     */
    private fun String.replaceFqNames(mappings: Map<FqName, FqName>): String {
        val candidates = mappings.entries
            .map { (oldFqName, newFqName) -> oldFqName.asString() to newFqName.asString() }
            .sortedByDescending { (oldFqName, _) -> oldFqName.length }
        val result = StringBuilder(length)
        var index = 0
        var inLineComment = false
        var inBlockComment = false
        var quote: Char? = null
        while (index < length) {
            val current = this[index]
            val next = getOrNull(index + 1)
            when {
                inLineComment -> {
                    result.append(current)
                    if (current == '\n') inLineComment = false
                    index++
                }
                inBlockComment -> {
                    result.append(current)
                    if (current == '*' && next == '/') {
                        result.append(next)
                        index += 2
                        inBlockComment = false
                    } else {
                        index++
                    }
                }
                quote != null -> {
                    result.append(current)
                    if (current == '\\' && next != null) {
                        result.append(next)
                        index += 2
                    } else {
                        if (current == quote) quote = null
                        index++
                    }
                }
                current == '/' && next == '/' -> {
                    result.append(current).append(next)
                    index += 2
                    inLineComment = true
                }
                current == '/' && next == '*' -> {
                    result.append(current).append(next)
                    index += 2
                    inBlockComment = true
                }
                current == '\'' || current == '\"' -> {
                    result.append(current)
                    quote = current
                    index++
                }
                else -> {
                    val match = candidates.firstOrNull { (oldFqName, _) ->
                        startsWith(oldFqName, index) && canStartFqNameAt(index) && canEndFqNameAt(index + oldFqName.length)
                    }
                    if (match == null) {
                        result.append(current)
                        index++
                    } else {
                        result.append(match.second)
                        index += match.first.length
                    }
                }
            }
        }
        return result.toString()
    }

    /** Rejects a match that continues a longer identifier or qualified name on its left. */
    private fun String.canStartFqNameAt(index: Int): Boolean {
        val previous = index - 1
        return previous !in indices || (!this[previous].isLetterOrDigit() && this[previous] != '_' && this[previous] != '.')
    }

    /** Rejects a match that continues a longer identifier on its right; `.member` suffixes are kept. */
    private fun String.canEndFqNameAt(index: Int): Boolean =
        index !in indices || (!this[index].isLetterOrDigit() && this[index] != '_')
}
