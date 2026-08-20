package org.ttt.autogenesis.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSType
import org.ttt.autogenesis.network.RpcDirection
import org.ttt.autogenesis.network.RpcMethod
import kotlin.io.FileAlreadyExistsException

/**
 * KSP processor for generating RPC handler registration code.
 *
 * @property codeGenerator Code generator for creating files
 * @property logger Logger for error reporting
 */
class RpcProcessor(
    private val codeGenerator : CodeGenerator,
    private val logger : KSPLogger
) : SymbolProcessor
{

    /**
     * Processes symbols annotated with [RpcMethod].
     *
     * @param resolver Symbol resolver
     * @return List of unprocessed symbols
     */
    override fun process(resolver : Resolver) : List<KSAnnotated>
    {
        val annotationName = RpcMethod::class.qualifiedName ?: return emptyList()
        val functions = resolver.getSymbolsWithAnnotation(annotationName)
            .filterIsInstance<KSFunctionDeclaration>()
            .filter { Modifier.SUSPEND in it.modifiers }
        val grouped = functions.mapNotNull { function ->
            val owner = function.parentDeclaration as? KSClassDeclaration ?: run {
                logger.error("Rpc handlers must live inside a class or object", function)
                null
            }
            owner?.let { it to function }
        }.groupBy({ it.first }, { it.second })
        
        // Generate individual class registration functions
        grouped.forEach { (owner, fns) -> generateForClass(owner, fns) }
        
        // Generate master registration / initializer module
        generateMasterRegistration(grouped.keys)
        
        return emptyList()
    }

    /**
     * Generates RPC registration code for a class and its methods.
     *
     * @param owner Class containing RPC methods
     * @param functions List of RPC methods in the class
     */
    private fun generateForClass(owner : KSClassDeclaration, functions : List<KSFunctionDeclaration>)
    {
        val pkg = owner.packageName.asString()
        val className = owner.simpleName.asString()
        val targetType = owner.qualifiedName?.asString() ?: run {
            logger.error("Unable to determine qualified name for ${className}", owner)
            return
        }
        val suffix = "RpcHandlers"
        val registrationName = if (
            className.length > suffix.length &&
            className.endsWith(suffix)
        ) {
            className.dropLast(suffix.length)
        } else {
            className
        }.ifEmpty { className }
        val fileName = "Generated${registrationName}RpcBindings"
        val dependencyFiles = mutableSetOf<KSFile>()
        owner.containingFile?.let { dependencyFiles.add(it) }
        functions.mapNotNull { it.containingFile }.forEach { dependencyFiles.add(it) }
        val dependencies = if (dependencyFiles.isEmpty()) Dependencies(true) else Dependencies(true, *dependencyFiles.toTypedArray())

        val providerTargetExpression = if (owner.classKind == ClassKind.OBJECT) {
            targetType
        } else {
            "$targetType()"
        }
        val providerClassName = "${registrationName}RpcHandlersRegistrationProvider"
        val providerPropertyName = "_${registrationName.replaceFirstChar { it.lowercaseChar() }}RpcHandlersProvider"

        val file = codeGenerator.createNewFile(dependencies, pkg, fileName)
        file.bufferedWriter().use { writer ->
            writer.appendLine("package $pkg")
            writer.appendLine()
            writer.appendLine("import org.ttt.autogenesis.network.*")
            writer.appendLine("import kotlinx.serialization.builtins.*")
            writer.appendLine()
            writer.appendLine("internal fun register${registrationName}RpcHandlers(rpcRegistry : RpcRegistry, target : $targetType)")
            writer.appendLine("{")
            functions.sortedBy { it.simpleName.asString() }.forEach { function ->
                generateFunctionBinding(writer, function)
            }
            writer.appendLine("}")
            writer.appendLine()
            writer.appendLine("internal class $providerClassName(private val target : $targetType) : RpcRegistrationProvider")
            writer.appendLine("{")
            writer.appendLine("    override fun register(rpcRegistry : RpcRegistry)")
            writer.appendLine("    {")
            writer.appendLine("        register${registrationName}RpcHandlers(rpcRegistry, target)")
            writer.appendLine("    }")
            writer.appendLine("}")
            writer.appendLine()
            writer.appendLine("internal val $providerPropertyName = $providerClassName($providerTargetExpression).also {")
            writer.appendLine("    RpcRegistrationCollector.registerProvider(it)")
            writer.appendLine("}")
        }
    }

    /**
     * Generates binding code for a single RPC method.
     *
     * @param writer Output writer
     * @param function RPC method function
     */
    private fun generateFunctionBinding(writer : Appendable, function : KSFunctionDeclaration)
    {
        val annotation = function.annotations.firstOrNull {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == RpcMethod::class.qualifiedName
        } ?: return
        val methodName = annotation.arguments.firstOrNull { it.name?.asString() == "name" }?.value as? String ?: return
        val directionArg = annotation.arguments.firstOrNull { it.name?.asString() == "direction" }
        val directionName = directionArg?.value?.toString()?.substringAfterLast('.')
        val direction = directionName?.let { name ->
            try {
                RpcDirection.valueOf(name)
            } catch (err : IllegalArgumentException) {
                null
            }
        } ?: RpcDirection.BOTH
        val parameters = function.parameters
        if (parameters.isEmpty()) {
            logger.error("Rpc handler must declare RpcCallContext as first parameter", function)
            return
        }
        val contextParam = parameters.first()
        val contextType = contextParam.type.resolve()
        if (contextType.declaration.qualifiedName?.asString() != "org.ttt.autogenesis.network.RpcCallContext") {
            logger.error("First parameter must be RpcCallContext", function)
            return
        }

        if (parameters.size > 2) {
            logger.error("Rpc handler can only accept RpcCallContext plus one payload parameter", function)
            return
        }

        val payloadParam = parameters.getOrNull(1)
        val hasPayload = payloadParam != null
        val payloadSerializer = payloadParam?.type?.resolve()?.let { serializerExpression(it, function) }
        if (hasPayload && payloadSerializer == null) {
            logger.error("Unable to determine serializer for payload type", function)
            return
        }

        val returnType = function.returnType?.resolve()
        val isFlowReturn = returnType?.declaration?.qualifiedName?.asString() == "kotlinx.coroutines.flow.Flow"
        val streamElementType = if (isFlowReturn) {
            val argumentType = returnType.arguments.singleOrNull()?.type
            if (argumentType == null) {
                logger.error("Streaming RPC must specify a result type", function)
                return
            }
            argumentType.resolve()
        } else {
            null
        }
        val streamResultSerializer = streamElementType?.let { serializerExpression(it, function) }
        if (isFlowReturn && streamResultSerializer == null) {
            logger.error("Unable to determine serializer for streaming result type", function)
            return
        }
        val isUnitReturn = !isFlowReturn && returnType?.declaration?.qualifiedName?.asString() == "kotlin.Unit"
        val resultSerializer = if (!isFlowReturn && !isUnitReturn) returnType?.let { serializerExpression(it, function) } else null
        if (!isFlowReturn && !isUnitReturn && resultSerializer == null) {
            logger.error("Unable to determine serializer for return type", function)
            return
        }

        writer.appendLine()
        if (isFlowReturn) {
            val streamSerializer = streamResultSerializer!!
            if (hasPayload) {
                writer.appendLine("    rpcRegistry.registerStream(")
                writer.appendLine("        method = \"$methodName\",")
                writer.appendLine("        direction = RpcDirection.${direction.name},")
                writer.appendLine("        serializer = $payloadSerializer,")
                writer.appendLine("        resultSerializer = $streamSerializer")
                writer.appendLine("    ) { ctx, payload ->")
                writer.appendLine("        target.${function.simpleName.asString()}(ctx, payload)")
                writer.appendLine("    }")
            } else {
                writer.appendLine("    rpcRegistry.registerStream(")
                writer.appendLine("        method = \"$methodName\",")
                writer.appendLine("        direction = RpcDirection.${direction.name},")
                writer.appendLine("        resultSerializer = $streamSerializer")
                writer.appendLine("    ) { ctx, _ ->")
                writer.appendLine("        target.${function.simpleName.asString()}(ctx)")
                writer.appendLine("    }")
            }
        } else if (hasPayload) {
            writer.appendLine("    rpcRegistry.registerTyped(")
            writer.appendLine("        method = \"$methodName\",")
            writer.appendLine("        direction = RpcDirection.${direction.name},")
            writer.appendLine("        serializer = $payloadSerializer,")
            writer.appendLine("        resultSerializer = ${resultSerializer ?: "kotlin.Unit.serializer()"}")
            writer.appendLine("    ) { ctx, payload ->")
            if (isUnitReturn) {
                writer.appendLine("        target.${function.simpleName.asString()}(ctx, payload)")
                writer.appendLine("        null")
            } else {
                writer.appendLine("        val response = target.${function.simpleName.asString()}(ctx, payload)")
                writer.appendLine("        response")
            }
            writer.appendLine("    }")
        } else {
            writer.appendLine("    rpcRegistry.register(\"$methodName\", RpcDirection.${direction.name}) { ctx, _ ->")
            if (isUnitReturn) {
                writer.appendLine("        target.${function.simpleName.asString()}(ctx)")
                writer.appendLine("        null")
            } else {
                writer.appendLine("        val response = target.${function.simpleName.asString()}(ctx)")
                writer.appendLine("        response?.let { RpcJson.encodeToJsonElement($resultSerializer, it) }")
            }
            writer.appendLine("    }")
        }
    }

    /**
     * Generates serializer expression for a given type.
     *
     * @param type Type to generate serializer for
     * @param owner Function owning the type
     * @return Serializer expression string or null if unsupported
     */
    private fun serializerExpression(type : KSType, owner : KSFunctionDeclaration) : String?
    {
        if (type.arguments.isNotEmpty()) {
            logger.error("Generic types are not supported for RPC payloads/results", owner)
            return null
        }
        val declarationName = type.declaration.qualifiedName?.asString() ?: run {
            logger.error("Unable to resolve serializer for ${type.declaration.simpleName.asString()}", owner)
            return null
        }
        return when (declarationName) {
            "kotlin.String" -> "kotlin.String.Companion.serializer()"
            "kotlin.Int" -> "kotlin.Int.Companion.serializer()"
            "kotlin.Long" -> "kotlin.Long.Companion.serializer()"
            "kotlin.Boolean" -> "kotlin.Boolean.Companion.serializer()"
            "kotlin.Double" -> "kotlin.Double.Companion.serializer()"
            "kotlin.Float" -> "kotlin.Float.Companion.serializer()"
            else -> "${declarationName}.serializer()"
        }
    }

    /**
     * Generates master registration initializer that ensures all RPC providers run when the generated module loads.
     *
     * @param owners Set of RPC handler classes
     */
    private fun generateMasterRegistration(owners: Set<KSClassDeclaration>)
    {
        val masterPackage = "org.ttt.autogenesis.network.generated"
        val dependencies = Dependencies(true, *owners.mapNotNull { it.containingFile }.toTypedArray())

        try {
            val file = codeGenerator.createNewFile(dependencies, masterPackage, "GeneratedRpcMasterRegistration")
            file.bufferedWriter().use { writer ->
                writer.appendLine("package $masterPackage")
                writer.appendLine()
                writer.appendLine("internal val _rpcRegistrationInitializer = run {")

                owners
                    .sortedBy { it.qualifiedName?.asString() ?: it.simpleName.asString() }
                    .forEach { owner ->
                        val className = owner.simpleName.asString()
                        val suffix = "RpcHandlers"
                        val registrationName = if (
                            className.length > suffix.length &&
                            className.endsWith(suffix)
                        ) {
                            className.dropLast(suffix.length)
                        } else {
                            className
                        }.ifEmpty { className }

                        val providerPropertyName = "_${registrationName.replaceFirstChar { it.lowercaseChar() }}RpcHandlersProvider"

                        writer.appendLine("    // Force initialization of ${registrationName} registration")
                        writer.appendLine("    ${owner.packageName.asString()}.$providerPropertyName")
                    }

                writer.appendLine("}")
            }
        } catch (err: FileAlreadyExistsException) {
            logger.warn("GeneratedRpcMasterRegistration already exists; skipping regeneration")
        }
    }
}