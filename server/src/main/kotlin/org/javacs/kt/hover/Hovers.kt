package org.javacs.kt.hover

import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkedString
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassifierDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getQualifiedExpressionForSelector
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.renderer.ClassifierNamePolicy
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.renderer.RenderingFormat
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.calls.callUtil.getType
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowValueFactory
import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.javacs.kt.CompiledFile
import org.javacs.kt.completion.DECL_RENDERER
import org.javacs.kt.position.position
import org.javacs.kt.util.findParent
import org.javacs.kt.signaturehelp.getDocString

fun hoverAt(file: CompiledFile, cursor: Int): Hover? {
    val (ref, target) = file.referenceAtPoint(cursor) ?: return typeHoverAt(file, cursor)
    val javaDoc = getDocString(file, cursor)
    val location = ref.textRange
    val hoverText = DECL_RENDERER.render(target)
    val hover = Either.forRight<String, MarkedString>(MarkedString("kotlin", hoverText))
    val range = Range(
            position(file.content, location.startOffset),
            position(file.content, location.endOffset))
    return Hover(listOf(hover, Either.forLeft(javaDoc)), range)
}

private fun typeHoverAt(file: CompiledFile, cursor: Int): Hover? {
    val expression = file.parseAtPoint(cursor)?.findParent<KtExpression>() ?: return null
    var javaDoc: String = ""
    if (expression.children.size > 0 && expression.children[0] is KDoc)
        javaDoc = renderJavaDoc(expression.children[0].text)
    val scope = file.scopeAtPoint(cursor) ?: return null
    val hoverText = renderTypeOf(expression, file.bindingContextOf(expression, scope)) ?: return null
    return Hover(listOf(Either.forRight(MarkedString("kotlin", hoverText)), Either.forLeft(javaDoc)))
}

// Source: https://github.com/JetBrains/kotlin/blob/master/idea/src/org/jetbrains/kotlin/idea/codeInsight/KotlinExpressionTypeProvider.kt

private val TYPE_RENDERER: DescriptorRenderer by lazy { DescriptorRenderer.COMPACT.withOptions {
    textFormat = RenderingFormat.PLAIN
    classifierNamePolicy = object: ClassifierNamePolicy {
        override fun renderClassifier(classifier: ClassifierDescriptor, renderer: DescriptorRenderer): String {
            if (DescriptorUtils.isAnonymousObject(classifier)) {
                return "<anonymous object>"
            }
            return ClassifierNamePolicy.SHORT.renderClassifier(classifier, renderer)
        }
    }
} }

private fun renderJavaDoc(text: String): String {
    val split = text.split('\n')
    return split.mapIndexed { i, it ->
        var ret: String
        if (i == 0) ret = it.substring(it.indexOf("/**") + 3) // get rid of the start comment characters
        else if (i == split.size - 1) ret = it.substring(it.indexOf("*/") + 2) // get rid of the end comment characters
        else ret = it.substring(it.indexOf('*') + 1) // get rid of any leading *
        ret
    }.joinToString("\n")
}

private fun renderTypeOf(element: KtExpression, bindingContext: BindingContext): String? {
    if (element is KtCallableDeclaration) {
        val descriptor = bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, element]
        if (descriptor != null) {
            when (descriptor) {
                is CallableDescriptor -> return descriptor.returnType?.let(TYPE_RENDERER::renderType)
            }
        }
    }

    val expressionType = bindingContext[BindingContext.EXPRESSION_TYPE_INFO, element]?.type ?: element.getType(bindingContext)
    val result = expressionType?.let { TYPE_RENDERER.renderType(it) } ?: return null

    val smartCast = bindingContext[BindingContext.SMARTCAST, element]
    if (smartCast != null && element is KtReferenceExpression) {
        val declaredType = (bindingContext[BindingContext.REFERENCE_TARGET, element] as? CallableDescriptor)?.returnType
        if (declaredType != null) {
            return result + " (smart cast from " + TYPE_RENDERER.renderType(declaredType) + ")"
        }
    }
    return result
}
