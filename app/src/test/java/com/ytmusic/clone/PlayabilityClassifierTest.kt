package com.ytmusic.clone

import com.ytmusic.clone.data.network.PlayabilityClassifier
import com.ytmusic.clone.data.network.PlayabilityClassifier.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

/** v7.19: whole-word matching for age / bot classification (pure JVM, no Android needed). */
class PlayabilityClassifierTest {

    private fun kind(status: String, reason: String) = PlayabilityClassifier.classify(status, reason)

    @Test fun realBotCheckIsBotCheck() {
        assertEquals(Kind.BOT_CHECK, kind("LOGIN_REQUIRED", "Sign in to confirm you're not a bot"))
    }

    @Test fun botCheckContainingWordMessageIsStillBotCheck() {
        // "message" contains the letters "age" but is NOT the word "age" -> must stay a bot check
        assertEquals(
            Kind.BOT_CHECK,
            kind("LOGIN_REQUIRED", "Sign in to confirm you're not a bot. This message appears when traffic looks automated")
        )
    }

    @Test fun ageRestrictedVariantsAreVideoSpecific() {
        assertEquals(Kind.VIDEO_SPECIFIC, kind("LOGIN_REQUIRED", "Sign in to confirm your age"))
        assertEquals(Kind.VIDEO_SPECIFIC, kind("LOGIN_REQUIRED", "This video may be inappropriate for some users. Sign in to confirm your age."))
        assertEquals(Kind.VIDEO_SPECIFIC, kind("UNPLAYABLE", "This content is age-restricted"))
        assertEquals(Kind.VIDEO_SPECIFIC, kind("LOGIN_REQUIRED", "AGE verification required"))
    }

    @Test fun wordsContainingAgeAreNotAgeRestricted() {
        // status ERROR is not a "video-specific" status, so without the word "age" it is tier-wide
        assertEquals(Kind.TIER_WIDE, kind("ERROR", "Could not manage the request, try a different language or storage"))
    }

    @Test fun wordsContainingBotAreNotBotCheck() {
        // "both" / "robot" contain "bot" but are not the word "bot"; LOGIN_REQUIRED without bot word is video-specific
        assertEquals(Kind.VIDEO_SPECIFIC, kind("LOGIN_REQUIRED", "Sign in to see both versions"))
        assertEquals(Kind.VIDEO_SPECIFIC, kind("LOGIN_REQUIRED", "Robot cleanup required"))
    }

    @Test fun botWordOnlyCountsForLoginRequired() {
        assertEquals(Kind.VIDEO_SPECIFIC, kind("UNPLAYABLE", "You are not a bot"))
        assertEquals(Kind.TIER_WIDE, kind("ERROR", "You are not a bot"))
    }

    @Test fun otherStatusesAreVideoSpecificOrTierWide() {
        assertEquals(Kind.VIDEO_SPECIFIC, kind("UNPLAYABLE", "Video unavailable"))
        assertEquals(Kind.VIDEO_SPECIFIC, kind("CONTENT_CHECK_REQUIRED", "Check content"))
        assertEquals(Kind.TIER_WIDE, kind("ERROR", "Something went wrong"))
    }
}
