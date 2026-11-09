package dev.donut.client.staff;

import dev.donut.client.config.StaffDetectorSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.donut.client.staff.StaffDetectorEngine.*;

class StaffDetectorEngineTest {
    private final UUID staff = UUID.fromString("c458951b-707e-486b-a35a-d46ddf1411da");
    private final StaffDetectorSettings settings = new StaffDetectorSettings();
    private final AtomicLong time = new AtomicLong();
    private final StaffDetectorEngine engine = new StaffDetectorEngine(settings, time::get);
    private void watch() { engine.watch(List.of(new StaffDetectorSettings.Member(staff.toString(), "KnownStaff"))); }
    private void advance(int seconds) { time.addAndGet(seconds * 1_000_000_000L); engine.tick(); }
    private View view() { return engine.views().getFirst(); }
    private Profile profile(boolean listed, boolean spectator) { return new Profile(staff, "KnownStaff", listed, spectator); }

    @Test void unverifiedNamesAndUnrelatedEntitiesNeverBecomeIdentifiedStaff() {
        engine.watch(List.of(new StaffDetectorSettings.Member("", "KnownStaff")));
        engine.roster(List.of(profile(true, true)));
        engine.bodies(List.of(new Body(staff, 10, true)));
        settings.completionChecks = true;
        for (int i = 0; i < 5; i++) engine.completions(List.of("KnownStaff"));
        assertTrue(engine.views().isEmpty()); assertTrue(engine.history().isEmpty());
        watch();
        assertEquals("Presence unknown", view().description());
        assertFalse(view().online() || view().nearby() || view().possibleHidden());
    }
    @Test void spectatorIsReportedModeAndDoesNotImplyProximityOrWatching() {
        watch(); engine.roster(List.of(profile(true, true)));
        assertTrue(view().spectator()); assertFalse(view().nearby());
        assertTrue(Double.isNaN(view().distance()));
        assertEquals(Kind.SPECTATOR, engine.drainNotifications().getFirst().kind());
        for (int i = 0; i < 20; i++) engine.roster(List.of(profile(true, true)));
        assertTrue(engine.drainNotifications().isEmpty());
        engine.roster(List.of(profile(true, false)));
        assertFalse(view().spectator());
    }
    @Test void unlistedPlayerInfoIsNotAPlayerInfoRemoval() {
        watch(); engine.roster(List.of(profile(true, false)));
        engine.roster(List.of(profile(false, false))); advance(10);
        assertTrue(view().online()); assertFalse(view().listed()); assertFalse(view().rosterLost());
        assertTrue(view().description().contains("unlisted")); assertTrue(engine.history().isEmpty());
    }
    @Test void invisibleEntityRequiresKnownUuidFiniteDistanceAndConfiguredRange() {
        watch();
        engine.bodies(List.of(new Body(UUID.randomUUID(), 1, true), new Body(staff, 65, true)));
        assertFalse(view().nearby()); assertTrue(engine.history().isEmpty());
        engine.bodies(List.of(new Body(staff, Double.NaN, true))); assertFalse(view().nearby());
        engine.bodies(List.of(new Body(staff, 64, true)));
        assertTrue(view().nearby() && view().invisible()); assertFalse(view().online());
        assertEquals(Kind.INVISIBLE_NEARBY, engine.drainNotifications().getFirst().kind());
        engine.bodies(List.of(new Body(staff, 12, false)));
        assertTrue(view().nearby()); assertFalse(view().invisible());
        engine.bodies(List.of()); assertFalse(view().nearby()); assertTrue(Double.isNaN(view().distance()));
    }
    @Test void completionsNeedRepeatedExactNamesAndNeverEstablishDistanceOrOnlineStatus() {
        watch(); settings.completionChecks = true;
        engine.completions(List.of("KnownStaff123"));
        engine.completions(List.of("KnownStaff")); assertFalse(view().possibleHidden());
        advance(10); engine.completions(List.of("knownstaff")); assertFalse(view().possibleHidden());
        advance(10); engine.completions(List.of("KNOWNSTAFF"));
        assertTrue(view().possibleHidden()); assertFalse(view().online() || view().nearby());
        var event = engine.drainNotifications().getFirst();
        assertEquals(Kind.COMPLETION_MISMATCH, event.kind()); assertTrue(event.possible());
        engine.completions(List.of("KnownStaff")); assertTrue(engine.drainNotifications().isEmpty());
    }
    @Test void emptyResponsesTimeoutsAndLongGapsBreakCompletionStreaks() {
        watch(); settings.completionChecks = true;
        engine.completions(List.of("KnownStaff")); engine.completions(List.of());
        engine.completions(List.of("KnownStaff")); engine.completions(List.of("KnownStaff"));
        assertFalse(view().possibleHidden());
        engine.clearCompletions(); engine.completions(List.of("KnownStaff"));
        advance(16); engine.completions(List.of("KnownStaff")); engine.completions(List.of("KnownStaff"));
        assertFalse(view().possibleHidden());
    }
    @Test void presenceAndFeatureChangesClearCompletionHintsAndEvidenceExpires() {
        watch(); settings.completionChecks = true;
        for (int i = 0; i < 3; i++) engine.completions(List.of("KnownStaff"));
        assertTrue(view().possibleHidden()); advance(settings.evidenceSeconds); assertFalse(view().possibleHidden());
        for (int i = 0; i < 3; i++) engine.completions(List.of("KnownStaff"));
        engine.roster(List.of(profile(true, false))); assertFalse(view().possibleHidden());
        engine.roster(List.of());
        for (int i = 0; i < 3; i++) engine.completions(List.of("KnownStaff"));
        assertTrue(view().possibleHidden()); settings.completionChecks = false; engine.tick();
        assertFalse(view().possibleHidden());
        assertTrue(engine.drainNotifications().stream().noneMatch(event -> event.kind() == Kind.COMPLETION_MISMATCH));
    }
    @Test void removalWaitsEightSecondsAndKeepsLogoutVanishAndTransfersAmbiguous() {
        watch(); engine.roster(List.of(profile(true, false))); engine.roster(List.of());
        advance(7); assertFalse(view().rosterLost()); assertTrue(engine.history().isEmpty());
        advance(1); assertTrue(view().rosterLost()); assertFalse(view().online());
        assertTrue(engine.history().getFirst().detail().contains("logout, transfer and vanish"));
        assertTrue(engine.drainNotifications().isEmpty(), "Weak removal alerts default off");
        advance(settings.evidenceSeconds); assertEquals("Presence unknown", view().description());
    }
    @Test void fastReappearanceSuppressesRemovalHintAndDoesNotRetainSpectatorMode() {
        watch(); settings.rosterAlerts = true;
        engine.roster(List.of(profile(true, true))); engine.drainNotifications();
        engine.roster(List.of()); advance(7);
        engine.roster(List.of(profile(true, false))); advance(2);
        assertTrue(view().online()); assertFalse(view().spectator() || view().rosterLost());
        assertTrue(engine.drainNotifications().isEmpty());
    }
    @Test void cooldownsArePerStaffAndPerSignalAndDisabledAlertsStillHaveHistory() {
        watch(); settings.spectatorAlerts = false;
        engine.roster(List.of(profile(true, true))); assertTrue(engine.drainNotifications().isEmpty());
        assertEquals(Kind.SPECTATOR, engine.history().getFirst().kind());
        engine.bodies(List.of(new Body(staff, 1, true)));
        assertEquals(Kind.INVISIBLE_NEARBY, engine.drainNotifications().getFirst().kind());
        engine.bodies(List.of()); engine.bodies(List.of(new Body(staff, 1, true)));
        assertTrue(engine.drainNotifications().isEmpty());
        advance(settings.alertCooldownSeconds); engine.bodies(List.of()); engine.bodies(List.of(new Body(staff, 1, true)));
        assertEquals(1, engine.drainNotifications().size());
    }
    @Test void queuesAndHistoryStayBoundedAndRemovingStaffCancelsPendingAlerts() {
        List<StaffDetectorSettings.Member> members = new ArrayList<>();
        List<Profile> profiles = new ArrayList<>(); List<Body> bodies = new ArrayList<>();
        for (int i = 0; i < StaffDetectorSettings.MAX_STAFF; i++) {
            UUID id = new UUID(0, i + 1);
            members.add(new StaffDetectorSettings.Member(id.toString(), "Staff" + i));
            profiles.add(new Profile(id, "Staff" + i, true, true)); bodies.add(new Body(id, 5, true));
        }
        engine.watch(members); engine.roster(profiles); engine.bodies(bodies);
        assertEquals(MAX_HISTORY, engine.history().size());
        assertEquals(MAX_NOTIFICATIONS, engine.drainNotifications().size());
        advance(settings.alertCooldownSeconds); engine.bodies(List.of()); engine.bodies(bodies);
        engine.watch(List.of()); assertTrue(engine.drainNotifications().isEmpty());
        engine.clear(); assertTrue(engine.views().isEmpty() && engine.history().isEmpty());
    }
}
