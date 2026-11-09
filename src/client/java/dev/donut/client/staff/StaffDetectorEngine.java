package dev.donut.client.staff;

import dev.donut.client.config.StaffDetectorSettings;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Pure observation model. Absence of packets always leaves hidden presence unknown. */
public final class StaffDetectorEngine {
    public static final int MAX_HISTORY = 128, MAX_NOTIFICATIONS = 32;
    public enum Kind { SPECTATOR, INVISIBLE_NEARBY, COMPLETION_MISMATCH, ROSTER_LOSS }
    public record Profile(UUID id, String name, boolean listed, boolean spectator) { }
    public record Body(UUID id, double distance, boolean invisible) { }
    public record Evidence(UUID id, String name, Kind kind, long timeNanos, String detail) {
        public boolean possible() { return kind == Kind.COMPLETION_MISMATCH || kind == Kind.ROSTER_LOSS; }
    }
    public record View(UUID id, String name, boolean online, boolean listed, boolean spectator,
                       boolean nearby, boolean invisible, double distance, boolean possibleHidden,
                       boolean rosterLost, long lastObservedNanos) {
        public String description() {
            List<String> parts = new ArrayList<>();
            if (nearby) parts.add((invisible ? "Invisible nearby" : "Nearby") + " (" + Math.round(distance) + "m)");
            if (spectator) parts.add("Reported spectator");
            if (possibleHidden) parts.add("Possible hidden presence: completion mismatch");
            else if (online && !listed) parts.add("Player info present; unlisted in tab");
            else if (online && parts.isEmpty()) parts.add("Player info present");
            if (rosterLost && !online && !possibleHidden) parts.add("Roster removed; presence unknown");
            return parts.isEmpty() ? "Presence unknown" : String.join(" | ", parts);
        }
        public int priority() { return invisible && nearby ? 4 : spectator ? 3 : possibleHidden ? 2 : nearby ? 1 : 0; }
    }
    private static final class State {
        final UUID id;
        String name;
        boolean online, listed, spectator, nearby, invisible, lossReported;
        double distance = Double.NaN;
        long lastObserved = -1, removedAt = -1, completionAt = -1, lossAt = -1;
        int completionHits;
        final Map<Kind, Long> notified = new HashMap<>();
        State(UUID id, String name) { this.id = id; this.name = name; }
    }
    private final StaffDetectorSettings settings;
    private final LongSupplier clock;
    private final Map<UUID, State> states = new LinkedHashMap<>();
    private final ArrayDeque<Evidence> history = new ArrayDeque<>(), notifications = new ArrayDeque<>();
    private long lastCompletion = -1;

    public StaffDetectorEngine(StaffDetectorSettings settings, LongSupplier clock) {
        this.settings = settings; this.clock = clock;
    }
    public void watch(Collection<StaffDetectorSettings.Member> members) {
        Set<UUID> retained = new HashSet<>();
        for (var member : members) {
            UUID id = member.id(); if (id == null) continue;
            retained.add(id);
            State state = states.computeIfAbsent(id, ignored -> new State(id, member.label()));
            if (!member.name.isEmpty()) state.name = member.name;
        }
        states.keySet().retainAll(retained);
        notifications.removeIf(event -> !retained.contains(event.id()));
    }
    public void roster(Collection<Profile> profiles) {
        long now = clock.getAsLong();
        Map<UUID, Profile> current = new HashMap<>();
        for (Profile profile : profiles) current.put(profile.id(), profile);
        for (State state : states.values()) {
            Profile info = current.get(state.id);
            if (info == null) {
                if (state.online) {
                    state.removedAt = now; state.lossReported = false;
                    state.completionHits = 0; state.completionAt = -1;
                }
                state.online = state.listed = state.spectator = false;
            } else {
                boolean enteredSpectator = !state.spectator && info.spectator();
                state.online = true; state.listed = info.listed(); state.spectator = info.spectator();
                state.removedAt = state.lossAt = -1; state.lastObserved = now;
                if (StaffDetectorSettings.validName(info.name())) state.name = info.name();
                if (state.listed) { state.completionAt = -1; state.completionHits = 0; }
                if (enteredSpectator) emit(state, Kind.SPECTATOR, "Server reports spectator mode; location and viewing target are unknown.", settings.spectatorAlerts);
            }
        }
    }
    public void bodies(Collection<Body> bodies) {
        long now = clock.getAsLong();
        Map<UUID, Body> current = new HashMap<>();
        for (Body body : bodies) if (Double.isFinite(body.distance()) && body.distance() >= 0) current.put(body.id(), body);
        for (State state : states.values()) {
            Body body = current.get(state.id);
            boolean nearby = body != null && body.distance() <= settings.nearbyRange;
            boolean invisible = nearby && body.invisible();
            boolean enteredInvisible = invisible && !(state.nearby && state.invisible);
            state.nearby = nearby; state.invisible = invisible;
            state.distance = nearby ? body.distance() : Double.NaN;
            if (nearby) state.lastObserved = now;
            if (enteredInvisible) emit(state, Kind.INVISIBLE_NEARBY,
                    "Known staff entity is invisible within " + settings.nearbyRange + " blocks; vanish and activity are unconfirmed.", settings.invisibilityAlerts);
        }
    }
    /** Called only for the detector's matched, live request. A missing result breaks the streak. */
    public void completions(Collection<String> names) {
        if (!settings.completionChecks) { clearCompletions(); return; }
        long now = clock.getAsLong();
        if (lastCompletion >= 0 && now - lastCompletion > seconds(settings.completionIntervalSeconds + 5)) clearCompletions();
        lastCompletion = now;
        Set<String> returned = new HashSet<>();
        for (String name : names) if (StaffDetectorSettings.validName(name)) returned.add(name.toLowerCase(Locale.ROOT));
        for (State state : states.values()) {
            if (state.listed || !returned.contains(state.name.toLowerCase(Locale.ROOT))) {
                state.completionHits = 0; state.completionAt = -1; continue;
            }
            state.completionHits = Math.min(settings.requiredSamples, state.completionHits + 1);
            if (state.completionHits >= settings.requiredSamples) {
                boolean first = state.completionAt < 0;
                state.completionAt = now;
                if (first) emit(state, Kind.COMPLETION_MISMATCH,
                        "Repeated /msg suggestions include this known name while tab omits it; possible hidden presence, not proof of vanish or proximity.", true);
            }
        }
    }
    public void clearCompletions() {
        lastCompletion = -1;
        for (State state : states.values()) { state.completionHits = 0; state.completionAt = -1; }
        notifications.removeIf(event -> event.kind() == Kind.COMPLETION_MISMATCH);
    }
    public void tick() {
        long now = clock.getAsLong();
        if (!settings.completionChecks) clearCompletions();
        for (State state : states.values()) {
            if (state.completionAt >= 0 && now - state.completionAt >= seconds(settings.evidenceSeconds)) {
                state.completionAt = -1; state.completionHits = 0;
            }
            if (state.removedAt >= 0 && !state.lossReported && now - state.removedAt >= seconds(8)) {
                state.lossReported = true; state.lossAt = now;
                emit(state, Kind.ROSTER_LOSS, "Player info disappeared; logout, transfer and vanish cannot be distinguished.", settings.rosterAlerts);
            }
            if (state.lossAt >= 0 && now - state.lossAt >= seconds(settings.evidenceSeconds)) state.lossAt = -1;
        }
    }
    private void emit(State state, Kind kind, String detail, boolean notify) {
        long now = clock.getAsLong();
        Long previous = state.notified.get(kind);
        if (previous != null && now - previous < seconds(settings.alertCooldownSeconds)) return;
        state.notified.put(kind, now);
        Evidence event = new Evidence(state.id, state.name, kind, now, detail);
        history.addFirst(event); while (history.size() > MAX_HISTORY) history.removeLast();
        if (notify) { notifications.addLast(event); while (notifications.size() > MAX_NOTIFICATIONS) notifications.removeFirst(); }
    }
    public List<View> views() {
        long now = clock.getAsLong();
        return states.values().stream().map(state -> new View(state.id, state.name, state.online, state.listed,
                        state.spectator, state.nearby, state.invisible, state.distance,
                        settings.completionChecks && state.completionAt >= 0 && now - state.completionAt < seconds(settings.evidenceSeconds),
                        state.lossAt >= 0 && now - state.lossAt < seconds(settings.evidenceSeconds), state.lastObserved))
                .sorted(Comparator.comparingInt(View::priority).reversed().thenComparing(View::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }
    public List<Evidence> history() { return List.copyOf(history); }
    public List<Evidence> drainNotifications() {
        List<Evidence> result = List.copyOf(notifications); notifications.clear(); return result;
    }
    public void clear() { states.clear(); history.clear(); notifications.clear(); lastCompletion = -1; }
    private static long seconds(int value) { return value * 1_000_000_000L; }
}
