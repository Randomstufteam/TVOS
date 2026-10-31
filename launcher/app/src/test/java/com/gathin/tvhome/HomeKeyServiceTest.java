package com.gathin.tvhome;

import org.junit.Test;
import java.util.Collections;
import java.util.Set;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HomeKeyServiceTest {
    private static final long SOON = 45_000L;

    @Test public void aTapIsAShortPress() {
        assertTrue(HomeKeyService.isShortPress(1_000, 1_120, 400));
    }

    @Test public void holdingPastTheTimeoutIsALongPress() {
        assertFalse(HomeKeyService.isShortPress(1_000, 1_400, 400));
        assertFalse(HomeKeyService.isShortPress(1_000, 3_000, 400));
    }

    @Test public void firstBindOfANewBootProbes() {
        assertTrue(HomeKeyService.firstBindAfterBoot(12, 11, SOON));
        assertTrue(HomeKeyService.firstBindAfterBoot(1, -1, SOON));
    }

    @Test public void aRebindInTheSameBootDoesNot() {
        assertFalse(HomeKeyService.firstBindAfterBoot(12, 12, SOON));
    }

    @Test public void aBindLongAfterBootDoesNot() {
        assertFalse(HomeKeyService.firstBindAfterBoot(12, 11, HomeKeyService.BOOT_WINDOW_MS));
    }

    @Test public void noBootCountMeansNoProbe() {
        assertFalse(HomeKeyService.firstBindAfterBoot(-1, -1, SOON));
    }

    private static final String TV = "com.mediatek.wwtv.tvcenter";

    @Test public void liveTvBeforeAnyKeyGoesHome() {
        assertTrue(HomeKeyService.powerOnRedirect(TV, TV, SOON, HomeKeyService.BOOT_WINDOW_MS));
    }

    @Test public void otherAppsAndInputsAreLeftAlone() {
        assertFalse(HomeKeyService.powerOnRedirect(TV, "com.netflix.ninja", SOON, HomeKeyService.BOOT_WINDOW_MS));
        assertFalse(HomeKeyService.powerOnRedirect(TV, null, SOON, HomeKeyService.BOOT_WINDOW_MS));
    }

    @Test public void afterAKeyOrTheWindowLiveTvStays() {
        assertFalse(HomeKeyService.powerOnRedirect(TV, TV, SOON, 0));
        assertFalse(HomeKeyService.powerOnRedirect(TV, TV, HomeKeyService.BOOT_WINDOW_MS, HomeKeyService.BOOT_WINDOW_MS));
    }

    @Test public void offMeansNothing() {
        assertFalse(HomeKeyService.powerOnRedirect("", TV, SOON, HomeKeyService.BOOT_WINDOW_MS));
    }

    @Test public void alwaysGoesHomeAfterScreenOnUntilAKey() {
        assertTrue(HomeKeyService.stillArmed(SOON, SOON + HomeKeyService.SCREEN_ON_WINDOW_MS));
        assertFalse(HomeKeyService.stillArmed(SOON, 0));
    }

    private static final String LX = "com.google.android.apps.tv.launcherx";
    private static final Set<String> LX_HOME = Collections.singleton(LX + ".home.HomeActivity");

    @Test public void theAssistantsWindowMarksResults() {
        assertTrue(HomeKeyService.marksAssistantResults(HomeKeyService.ASSISTANT, "x.VoiceActivity", LX, LX_HOME));
    }

    @Test public void theStockDeepLinkMarksResultsButItsHomeDoesNot() {
        assertTrue(HomeKeyService.marksAssistantResults(LX, LX + ".gateway.LauncherDeepLink", LX, LX_HOME));
        assertFalse(HomeKeyService.marksAssistantResults(LX, LX + ".home.HomeActivity", LX, LX_HOME));
    }

    @Test public void otherStockWindowsAreNotResults() {
        assertFalse(HomeKeyService.marksAssistantResults(LX, LX + ".profile.core.AccountVerificationActivity", LX, LX_HOME));
        assertFalse(HomeKeyService.marksAssistantResults(LX, LX + ".fullscreeneducation.dialogservice.DialogServiceLandingActivity", LX, LX_HOME));
    }

    @Test public void otherAppsAndNoRedirectMarkNothing() {
        assertFalse(HomeKeyService.marksAssistantResults("com.netflix.ninja", "com.netflix.ninja.MainActivity", LX, LX_HOME));
        assertFalse(HomeKeyService.marksAssistantResults(LX, LX + ".gateway.LauncherDeepLink", "", LX_HOME));
    }

    @Test public void theGearsSidePanelIsTheStockDashboard() {
        assertTrue(HomeKeyService.isStockDashboard(LX, LX + ".dashboard.DashboardActivity", LX));
        assertFalse(HomeKeyService.isStockDashboard(LX, LX + ".home.HomeActivity", LX));
        assertFalse(HomeKeyService.isStockDashboard(LX, LX + ".dashboard.DashboardActivity", ""));
    }

    @Test public void anotherAppEndsTheResultsPage() {
        assertTrue(HomeKeyService.endsAssistantResults("com.netflix.ninja", LX, "com.gathin.tvhome"));
    }

    @Test public void theStockLauncherTheAssistantAndHomeDoNot() {
        assertFalse(HomeKeyService.endsAssistantResults(LX, LX, "com.gathin.tvhome"));
        assertFalse(HomeKeyService.endsAssistantResults(HomeKeyService.ASSISTANT, LX, "com.gathin.tvhome"));
        assertFalse(HomeKeyService.endsAssistantResults("com.gathin.tvhome", LX, "com.gathin.tvhome"));
    }
}
