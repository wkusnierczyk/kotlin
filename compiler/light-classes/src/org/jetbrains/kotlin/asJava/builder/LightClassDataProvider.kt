/*
 * Copyright 2010-2016 JetBrains s.r.o.
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
 */

package org.jetbrains.kotlin.asJava.builder

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.java.stubs.PsiJavaFileStub
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.asJava.LightClassGenerationSupport
import org.jetbrains.kotlin.asJava.classes.getOutermostClassOrObject
import org.jetbrains.kotlin.codegen.CompilationErrorHandler
import org.jetbrains.kotlin.codegen.state.GenerationState
import org.jetbrains.kotlin.fileClasses.NoResolveFileClassesProvider
import org.jetbrains.kotlin.fileClasses.getFileClassType
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.psi.KtScript

abstract class LightClassDataProvider<T : LightClassDataHolder>(
        private val project: Project
) : CachedValueProvider<T> {
    abstract val isLocal: Boolean

    open val valueAbsent: Boolean = false

    override fun compute(): CachedValueProvider.Result<T>? {
        if (valueAbsent) return null

        return CachedValueProvider.Result.create(
                computeLightClassData(),
                if (isLocal) PsiModificationTracker.MODIFICATION_COUNT else PsiModificationTracker.OUT_OF_CODE_BLOCK_MODIFICATION_COUNT
        )
    }

    abstract fun computeLightClassData(): T
}

class LightClassDataProviderForClassOrObject(private val classOrObject: KtClassOrObject) :
        LightClassDataProvider<LightClassDataHolder>(classOrObject.project) {

    override val isLocal: Boolean get() = classOrObject.isLocal()


    override fun computeLightClassData(): LightClassDataHolder {
        val file = classOrObject.containingKtFile

        val packageFqName = file.packageFqName
        val generateClassFilter = object : GenerationState.GenerateClassFilter() {

            override fun shouldGeneratePackagePart(jetFile: KtFile): Boolean {
                return true
            }

            override fun shouldAnnotateClass(processingClassOrObject: KtClassOrObject): Boolean {
                return shouldGenerateClass(processingClassOrObject)
            }

            override fun shouldGenerateClassMembers(processingClassOrObject: KtClassOrObject): Boolean {
                if (classOrObject === processingClassOrObject) return true

                // process all children
                if (PsiTreeUtil.isAncestor(classOrObject, processingClassOrObject, true)) {
                    return true
                }

                // Local classes should be process by CodegenAnnotatingVisitor to
                // decide what class they should be placed in.
                //
                // Example:
                // class A
                // fun foo() {
                //     trait Z: A {}
                //     fun bar() {
                //         class <caret>O2: Z {}
                //     }
                // }
                // TODO: current method will process local classes in irrelevant declarations, it should be fixed.
                // We generate all enclosing classes

                if (classOrObject.isLocal() && processingClassOrObject.isLocal()) {
                    val commonParent = PsiTreeUtil.findCommonParent(classOrObject, processingClassOrObject)
                    return commonParent != null && commonParent !is PsiFile
                }

                return false
            }

            override fun shouldGenerateClass(processingClassOrObject: KtClassOrObject): Boolean {
                // generate outer classes but not their members
                return shouldGenerateClassMembers(processingClassOrObject) || PsiTreeUtil.isAncestor(processingClassOrObject, classOrObject, true)
            }

            override fun shouldGenerateScript(script: KtScript): Boolean {
                return PsiTreeUtil.isAncestor(script, classOrObject, false)
            }
        }

        return LightClassGenerationSupport.getInstance(classOrObject.project).createLightClassDataHolderForClassOrObject(classOrObject) {
            constructionContext ->
            buildLightClass(classOrObject.project, packageFqName, listOf(file), generateClassFilter, constructionContext) {
                state, files ->
                val packageCodegen = state.factory.forPackage(packageFqName, files)
                val packagePartType = state.fileClassesProvider.getFileClassType(file)
                val context = state.rootContext.intoPackagePart(packageCodegen.packageFragment, packagePartType, file)
                packageCodegen.generateClassOrObject(getOutermostClassOrObject(classOrObject), context)
                state.factory.asList()
            }
        }
    }

    override fun toString(): String {
        return this.javaClass.name + " for " + classOrObject.name
    }
}

sealed class LightClassDataProviderForFileFacade constructor(
        protected val project: Project, protected val facadeFqName: FqName
) : LightClassDataProvider<LightClassDataHolder>(project) {
    override val isLocal: Boolean get() = false
    abstract val files: Collection<KtFile>

    override val valueAbsent: Boolean
        get() = files.isEmpty()

    override fun computeLightClassData(): LightClassDataHolder {
        val generateClassFilter = object : GenerationState.GenerateClassFilter() {
            override fun shouldAnnotateClass(processingClassOrObject: KtClassOrObject): Boolean {
                return shouldGenerateClass(processingClassOrObject)
            }

            override fun shouldGenerateClass(processingClassOrObject: KtClassOrObject): Boolean {
                return KtPsiUtil.isLocal(processingClassOrObject)
            }

            override fun shouldGeneratePackagePart(jetFile: KtFile): Boolean {
                return true
            }

            override fun shouldGenerateScript(script: KtScript): Boolean {
                return false
            }
        }

        val packageFqName = facadeFqName.parent()

        return LightClassGenerationSupport.getInstance(project).createLightClassDataHolderForFacade(files) {
            constructionContext ->
            buildLightClass(project, packageFqName, files, generateClassFilter, constructionContext) generate@ {
                state, files ->
                if (!files.isEmpty()) {
                    val representativeFile = files.iterator().next()
                    val fileClassInfo = NoResolveFileClassesProvider.getFileClassInfo(representativeFile)
                    if (!fileClassInfo.withJvmMultifileClass) {
                        val codegen = state.factory.forPackage(representativeFile.packageFqName, files)
                        codegen.generate(CompilationErrorHandler.THROW_EXCEPTION)
                        state.factory.asList()
                        return@generate
                    }
                }

                val codegen = state.factory.forMultifileClass(facadeFqName, files)
                codegen.generate(CompilationErrorHandler.THROW_EXCEPTION)
                state.factory.asList()
            }
        }
    }

    override fun toString(): String {
        return this.javaClass.name + " for $facadeFqName"
    }

    // create delegate by relevant files in project source using LightClassGenerationSupport
    class ByProjectSource(
            project: Project,
            facadeFqName: FqName,
            private val searchScope: GlobalSearchScope
    ) : LightClassDataProviderForFileFacade(project, facadeFqName) {
        override val files: Collection<KtFile>
            get() = LightClassGenerationSupport.getInstance(project).findFilesForFacade(facadeFqName, searchScope)
    }

    // create delegate by single file
    class ByFile(
            project: Project,
            facadeFqName: FqName,
            private val file: KtFile
    ) : LightClassDataProviderForFileFacade(project, facadeFqName) {
        override val files: Collection<KtFile>
            get() = listOf(file)
    }
}


interface StubComputationTracker {
    fun onStubComputed(javaFileStub: PsiJavaFileStub)
}
