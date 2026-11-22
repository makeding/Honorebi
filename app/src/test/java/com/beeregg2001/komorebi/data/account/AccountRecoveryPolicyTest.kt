package com.beeregg2001.komorebi.data.account

import com.beeregg2001.komorebi.data.ConnectionSourcePolicy
import com.beeregg2001.komorebi.data.auth.HonomiSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountRecoveryPolicyTest {

    @Test
    fun `shouldPromptAccountRecovery returns true when store is empty and account has settings`() {
        val record = KomorebiAccount.Record(
            settings = mapOf("konomi_ip" to "192.168.1.10", "konomi_port" to "7000"),
        )
        assertTrue(shouldPromptAccountRecovery(hasConfiguredSettingsInStore = false, record = record))
    }

    @Test
    fun `shouldPromptAccountRecovery returns true when store is empty and account has session`() {
        val record = KomorebiAccount.Record(
            session = HonomiSession("https://192.168.1.10:7000", "token", 1, "alice"),
        )
        assertTrue(shouldPromptAccountRecovery(hasConfiguredSettingsInStore = false, record = record))
    }

    @Test
    fun `shouldPromptAccountRecovery returns false when store already has configured settings`() {
        val record = KomorebiAccount.Record(
            settings = mapOf("konomi_ip" to "192.168.1.10"),
        )
        assertFalse(shouldPromptAccountRecovery(hasConfiguredSettingsInStore = true, record = record))
    }

    @Test
    fun `shouldPromptAccountRecovery returns false when record is null or empty`() {
        assertFalse(shouldPromptAccountRecovery(hasConfiguredSettingsInStore = false, record = null))
        assertFalse(
            shouldPromptAccountRecovery(
                hasConfiguredSettingsInStore = false,
                record = KomorebiAccount.Record(),
            ),
        )
    }

    @Test
    fun `summarizeRecoverableAccount extracts userName and ignores placeholder konomi ip`() {
        val record = KomorebiAccount.Record(
            settings = mapOf(
                "backend_type" to "TIF",
                "konomi_ip" to ConnectionSourcePolicy.KONOMI_PLACEHOLDER,
                "edcb_ip" to "192.168.1.20",
                "edcb_port" to "4510",
                "smb_server_list" to """[{"id":"1"}]""",
            ),
            session = HonomiSession("https://192.168.1.10:7000", "token", 1, "taro"),
        )

        val summary = summarizeRecoverableAccount(record)
        assertEquals("taro", summary.userName)
        assertEquals(
            listOf("本体のテレビ入力 (TIF)", "EDCB (192.168.1.20:4510)", "SMB サーバー設定"),
            summary.connectionLabels,
        )
    }
}
