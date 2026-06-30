package org.ttt.autogenesis.server.config.env

import java.nio.charset.StandardCharsets

object ProcessEnvironmentCompat
{
    /**
     * Ensures the JVM's internal environment maps only hold the private types expected by newer Java releases.
     * Any existing entries are re-wrapped and the provided overrides are inserted afterwards.
     */
    fun patchEnvironment(updates: Map<String, String>)
    {
        if(updates.isEmpty())
        {
            return
        }

        try
        {
            val processEnvironmentClass = Class.forName("java.lang.ProcessEnvironment")
            val environmentField = processEnvironmentClass.getDeclaredField("theEnvironment").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val environmentMap = environmentField.get(null) as MutableMap<Any, Any>

            val variableClass = processEnvironmentClass.declaredClasses.firstOrNull { it.simpleName == "Variable" }
            val valueClass = processEnvironmentClass.declaredClasses.firstOrNull { it.simpleName == "Value" }

            val wrapKey = createWrapper(variableClass)
            val wrapValue = createWrapper(valueClass)

            environmentMap.normalizeAndPut(values = updates, wrapKey = wrapKey, wrapValue = wrapValue)

            try
            {
                val ciField = processEnvironmentClass.getDeclaredField("theCaseInsensitiveEnvironment").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                val ciMap = ciField.get(null) as MutableMap<Any, Any>
                ciMap.normalizeAndPut(values = updates, wrapKey = wrapKey, wrapValue = wrapValue)
            }
            catch(e: NoSuchFieldException)
            {
                println("ProcessEnvironmentCompat: theCaseInsensitiveEnvironment map not present on this JVM")
            }

            println("ProcessEnvironmentCompat: Applied environment overrides for ${updates.keys.joinToString()}")
        }
        catch(e: Exception)
        {
            println("ProcessEnvironmentCompat: Failed to patch ProcessEnvironment (${e.message})")
            throw RuntimeException("Failed to configure AccelByte environment variables", e)
        }
    }

    private fun createWrapper(clazz: Class<*>?): (String) -> Any
    {
        if(clazz == null)
        {
            return { it }
        }

        val candidateSignatures = listOf(
            arrayOf(String::class.java),
            arrayOf(ByteArray::class.java)
        )

        val method = candidateSignatures
            .mapNotNull { signature ->
                runCatching { clazz.getDeclaredMethod("valueOf", *signature) }.getOrNull()
            }
            .firstOrNull()

        return if(method != null)
        {
            method.isAccessible = true
            { value ->
                val argument = when(method.parameterTypes.first())
                {
                    ByteArray::class.java -> value.toByteArray(StandardCharsets.UTF_8)
                    else -> value
                }

                method.invoke(null, argument)
            }
        }
        else
        {
            { it }
        }
    }

    private fun MutableMap<Any, Any>.normalizeAndPut(
        values: Map<String, String>,
        wrapKey: (String) -> Any,
        wrapValue: (String) -> Any
    )
    {
        if(isNotEmpty())
        {
            val normalizedEntries = entries.map { entry ->
                val normalizedKey = entry.key.toString()
                val normalizedValue = normalizeValue(entry.value)
                wrapKey(normalizedKey) to wrapValue(normalizedValue)
            }

            clear()
            normalizedEntries.forEach { (key, value) ->
                this[key] = value
            }
        }

        values.forEach { (key, value) ->
            this[wrapKey(key)] = wrapValue(value)
        }
    }

    private fun normalizeValue(value: Any?): String
    {
        return when(value)
        {
            null -> ""
            is ByteArray -> String(value, StandardCharsets.UTF_8)
            else -> value.toString()
        }
    }
}
