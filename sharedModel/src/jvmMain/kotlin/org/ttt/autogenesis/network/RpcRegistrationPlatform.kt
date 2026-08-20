package org.ttt.autogenesis.network

/**
 * JVM implementation of RPC registration initialization using reflection.
 */
actual fun initializeRpcRegistrationsPlatform()
{
    try {
        Class.forName(RpcMasterRegistration.FQN)
    } catch (e: ClassNotFoundException) {
        // Module has no generated RPC handlers; ignore.
    } catch (e: Exception) {
        // Other errors when loading master registration are ignored.
    }
}

private object RpcMasterRegistration
{
    const val PACKAGE = "org.ttt.autogenesis.network.generated"
    const val CLASS = "GeneratedRpcMasterRegistrationKt"
    const val FQN = "$PACKAGE.$CLASS"
}