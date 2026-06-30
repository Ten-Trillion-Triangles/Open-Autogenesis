package matchmaking

import account.ByoCredentialStore
import account.FakeVirtualFileSystem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.server.vfs.VirtualFileSystem
import structs.account.AccountPlan
import structs.account.AccountSettings
import structs.account.BillingStatus
import structs.account.CostClass
import structs.rpcRequests.SubmitByoKeyRequest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that [AccountSettingsLookup] downgrades a player from [CostClass.BYO_KEY]
 * to [CostClass.PRO] when the settings record claims `bringYourOwnApiKey = true` but
 * the encrypted BYO slot is missing in the VFS. This is the "half-broken state"
 * self-heal path: the player should not be ranked above PRO on the matchmaker
 * ladder when we have no key to actually use.
 */
class AccountSettingsCostClassDowngradeTest
{
    private val masterKey: ByteArray = ByteArray(32) { (it + 21).toByte() }
    private lateinit var fakeVfs: FakeVirtualFileSystem

    @Before
    fun setUp()
    {
        fakeVfs = FakeVirtualFileSystem()
        ByoCredentialStore.vfsFactory = { fakeVfs }
        ByoCredentialStore.masterKey = masterKey
    }

    @After
    fun tearDown()
    {
        ByoCredentialStore.vfsFactory = { org.ttt.autogenesis.server.vfs.VirtualFileSystemManager.forUser(it) }
        ByoCredentialStore.masterKey = null
    }

    @Test
    fun byoFlagWithSlotReturnsByoKey() = runBlocking {
        seedAccountSettings("user-1", AccountSettings(bringYourOwnApiKey = true))
        ByoCredentialStore.upsert("user-1", validSubmit("user-1")).getOrThrow()
        val lookup = object : AccountSettingsLookup() {
            override fun vfsFactoryFor(userId: String): VirtualFileSystem = fakeVfs
        }
        assertEquals(CostClass.BYO_KEY, lookup.costClassFor("user-1"))
    }

    @Test
    fun byoFlagWithoutSlotDowngradesToPro() = runBlocking {
        // Settings claim BYO is on, but no slot is written to the VFS.
        seedAccountSettings("user-1", AccountSettings(bringYourOwnApiKey = true))
        val lookup = object : AccountSettingsLookup() {
            override fun vfsFactoryFor(userId: String): VirtualFileSystem = fakeVfs
        }
        assertEquals(CostClass.PRO, lookup.costClassFor("user-1"),
            "User with bringYourOwnApiKey=true but no slot must be downgraded to PRO")
    }

    @Test
    fun byoFlagWithoutSlotAttributesMarkByoFalse() = runBlocking {
        seedAccountSettings("user-1", AccountSettings(bringYourOwnApiKey = true))
        val lookup = object : AccountSettingsLookup() {
            override fun vfsFactoryFor(userId: String): VirtualFileSystem = fakeVfs
        }
        val attrs = lookup.attributesFor("user-1")
        assertEquals("PRO", attrs["cost_class"]?.content,
            "attributesFor must mirror the costClassFor downgrade")
        assertEquals(false, attrs["byo_api_key"]?.content?.toBoolean(),
            "byo_api_key attribute must be false when the slot is missing")
    }

    @Test
    fun byoFlagWithSlotAttributesMarkByoTrue() = runBlocking {
        seedAccountSettings("user-1", AccountSettings(bringYourOwnApiKey = true))
        ByoCredentialStore.upsert("user-1", validSubmit("user-1")).getOrThrow()
        val lookup = object : AccountSettingsLookup() {
            override fun vfsFactoryFor(userId: String): VirtualFileSystem = fakeVfs
        }
        val attrs = lookup.attributesFor("user-1")
        assertEquals("BYO_KEY", attrs["cost_class"]?.content)
        assertTrue(attrs["byo_api_key"]?.content?.toBoolean() == true)
    }

    @Test
    fun proUserUnaffectedByByoSlotPresence() = runBlocking {
        // A user with no BYO flag should never be probed for the slot.
        seedAccountSettings("user-1", AccountSettings(
            billingStatus = BillingStatus(plan = AccountPlan.PRO, autoRenew = true)
        ))
        val lookup = object : AccountSettingsLookup() {
            override fun vfsFactoryFor(userId: String): VirtualFileSystem = fakeVfs
        }
        assertEquals(CostClass.PRO, lookup.costClassFor("user-1"))
        val attrs = lookup.attributesFor("user-1")
        assertEquals("PRO", attrs["cost_class"]?.content)
        assertEquals(false, attrs["byo_api_key"]?.content?.toBoolean())
    }

    @Test
    fun freeUserStaysFreeWhenNoFlag() = runBlocking {
        seedAccountSettings("user-1", AccountSettings())  // bringYourOwnApiKey defaults to false
        val lookup = object : AccountSettingsLookup() {
            override fun vfsFactoryFor(userId: String): VirtualFileSystem = fakeVfs
        }
        assertEquals(CostClass.FREE, lookup.costClassFor("user-1"))
    }

    @Test
    fun byoFlagWithCorruptSlotDowngradesToPro() = runBlocking {
        // The settings record claims BYO is on, and a record exists in the VFS but
        // the ciphertext is unparseable. We expect a downgrade rather than a crash.
        seedAccountSettings("user-1", AccountSettings(bringYourOwnApiKey = true))
        // Inject a junk record directly via the fake.
        fakeVfs.saveUserRecord(
            "user-1",
            ByoCredentialStore.RECORD_KEY,
            kotlinx.serialization.json.JsonPrimitive("not-a-valid-json-object")
        )
        val lookup = object : AccountSettingsLookup() {
            override fun vfsFactoryFor(userId: String): VirtualFileSystem = fakeVfs
        }
        assertEquals(CostClass.PRO, lookup.costClassFor("user-1"))
    }

    @Test
    fun byoFlagWithoutSlotSubsidyMatchesPro() = runBlocking {
        seedAccountSettings("user-1", AccountSettings(bringYourOwnApiKey = true))
        val lookup = object : AccountSettingsLookup() {
            override fun vfsFactoryFor(userId: String): VirtualFileSystem = fakeVfs
        }
        assertEquals(CostClass.PRO.subsidyCapacity, lookup.subsidyFor("user-1"))
    }

    private fun validSubmit(userId: String): SubmitByoKeyRequest = SubmitByoKeyRequest(
        userId = userId,
        provider = "aws-bedrock",
        keyId = "AKIAIOSFODNN7EXAMPLE",
        secret = "secret-value",
        region = "us-east-1"
    )

    private suspend fun seedAccountSettings(userId: String, settings: AccountSettings)
    {
        val payload = RpcJson.encodeToJsonElement(AccountSettings.serializer(), settings)
        val envelope = kotlinx.serialization.json.buildJsonObject {
            put("value", payload)
        }
        fakeVfs.saveUserRecord(userId, "account-settings", envelope)
    }
}
