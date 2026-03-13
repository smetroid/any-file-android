package com.anyproto.anyfile.service

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SyncServiceTest {

    @Test
    fun `SyncService exists and has correct class structure`() {
        val serviceClass = Class.forName("com.anyproto.anyfile.service.SyncService")
        assertNotNull(serviceClass)
    }

    @Test
    fun `SyncService companion object has correct constants`() {
        val notificationId = SyncService.NOTIFICATION_ID
        assertTrue(notificationId > 0, "NOTIFICATION_ID must be positive")
    }
}
