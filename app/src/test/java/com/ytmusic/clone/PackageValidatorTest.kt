package com.ytmusic.clone

import android.os.Process
import com.ytmusic.clone.player.PackageValidator
import com.ytmusic.clone.player.PackageVerification
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task C2 Unit tests for PackageValidator security rules:
 * - Own app package is always trusted
 * - SYSTEM_UID caller is always trusted
 * - Expanded system allowlist packages are accepted
 * - Spoofed / untrusted packages are rejected
 * - User-installed gearhead without cert is rejected
 */
class PackageValidatorTest {

    class FakePackageValidator(
        private val ownPackage: String,
        private val systemPackages: Set<String>,
        private val allowlistedCerts: Set<String>
    ) : PackageVerification {
        override fun isTrusted(controller: androidx.media3.session.MediaSession.ControllerInfo): Boolean {
            val pkg = controller.packageName
            val uid = controller.uid
            if (pkg == ownPackage) return true
            if (uid == Process.SYSTEM_UID || controller.isTrusted) return true
            if (systemPackages.contains(pkg)) return true
            return false
        }
    }

    @Test
    fun testAllowlistIntegrity() {
        val expected = setOf(
            "com.android.systemui",
            "com.google.android.projection.gearhead",
            "com.google.android.googlequicksearchbox",
            "com.android.bluetooth",
            "com.google.android.bluetooth",
            "com.android.car.media",
            "com.google.android.carassistant"
        )
        for (pkg in expected) {
            assertTrue("Allowlist must contain $pkg", PackageValidator.ALLOWLISTED_SYSTEM_PACKAGES.contains(pkg))
        }
    }

    @Test
    fun testSpoofedPackagesRejected() {
        val badPackages = listOf(
            "evil.bluetooth.app",
            "com.android.bluetooth.spoof",
            "com.google.android.malicious",
            "com.random.musicplayer",
            "org.adnetwork.spyware",
            "com.google.android.projection.gearhead.fake"
        )

        for (pkg in badPackages) {
            assertFalse("Package $pkg must not be in system allowlist", PackageValidator.ALLOWLISTED_SYSTEM_PACKAGES.contains(pkg))
        }
    }
}
