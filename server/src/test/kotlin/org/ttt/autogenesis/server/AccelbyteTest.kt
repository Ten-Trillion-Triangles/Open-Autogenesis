package org.ttt.autogenesis.server

import accelbyte.login.Login
import accelbyte.login.Login.loginUser
import accelbyte.login.Login.sendRegistrationCode
import accelbyte.login.Login.verifyRegistrationCode
import kotlin.test.Ignore
import kotlin.test.Test

class AccelByteTest {

    @Test
    @Ignore("depends on external AccelByte service")
    fun testRegisterCode()
    {
        val result = sendRegistrationCode("contact+test400@tentrilliontriangles.com")
        println(result.error)
        println(result.success)
        println(result.hashCode())
    }

    fun testRegisterAccount()
    {

    }

    @Test
    @Ignore("depends on external AccelByte service")
    fun testVerifyCode()
    {
        val result = verifyRegistrationCode("contact+test400@tentrilliontriangles.com", "D0K6DHSB")
        println(result.error)
        println(result.success)
        println(result.hashCode())
    }


}
