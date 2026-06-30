package org.ttt.autogenesis.ksp

import com.google.auto.service.AutoService
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

/**
 * KSP service provider for [RpcProcessor].
 * Automatically discovered by KSP through service loader mechanism.
 */
@AutoService(SymbolProcessorProvider::class)
class RpcProcessorProvider : SymbolProcessorProvider
{
    /**
     * Creates a new [RpcProcessor] instance.
     *
     * @param environment KSP processing environment
     * @return New RPC processor instance
     */
    override fun create(environment : SymbolProcessorEnvironment) : SymbolProcessor =
        RpcProcessor(environment.codeGenerator, environment.logger)
}
