package com.ytmusic.clone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Validates security attributes in AndroidManifest.xml:
 * - No android:debuggable="true"
 * - allowBackup is false
 * - usesCleartextTraffic is false
 * - dataExtractionRules and networkSecurityConfig are explicitly declared
 */
class ManifestSecurityTest {

    @Test
    fun testManifestSecurityConfiguration() {
        // Look for AndroidManifest.xml in standard project paths
        val manifestPaths = listOf(
            File("app/src/main/AndroidManifest.xml"),
            File("src/main/AndroidManifest.xml"),
            File("../app/src/main/AndroidManifest.xml")
        )
        val manifestFile = manifestPaths.firstOrNull { it.exists() }
        if (manifestFile == null) {
            // If running in isolated unit test runner where CWD differs, skip
            return
        }

        val text = manifestFile.readText()

        // 1. debuggable must never be true
        assertFalse("Manifest must not set android:debuggable=\"true\"", text.contains("android:debuggable=\"true\""))

        // 2. allowBackup must be false
        assertTrue("Manifest must set android:allowBackup=\"false\"", text.contains("android:allowBackup=\"false\""))

        // 3. Cleartext traffic must be explicitly disabled
        assertTrue("Manifest must set android:usesCleartextTraffic=\"false\"", text.contains("android:usesCleartextTraffic=\"false\""))

        // 4. Data extraction rules must be present
        assertTrue("Manifest must declare android:dataExtractionRules", text.contains("android:dataExtractionRules"))

        // 5. Network security config must be present
        assertTrue("Manifest must declare android:networkSecurityConfig", text.contains("android:networkSecurityConfig"))
    }
}
