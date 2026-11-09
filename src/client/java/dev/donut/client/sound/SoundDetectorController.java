package dev.donut.client.sound;

import com.google.gson.GsonBuilder;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.SoundDetectorSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Client-thread adapter. Captures never send packets, load chunks, or write files. */
public final class SoundDetectorController {
    private static SoundDetectorController instance;
    private final ClientConfig config;
    private final ArrayDeque<SoundObservation> pending = new ArrayDeque<>();
    private final LinkedHashMap<Long, Card> cards = new LinkedHashMap<>();
    private final PriorityQueue<AudioCue> audio = new PriorityQueue<>(Comparator.comparingLong(AudioCue::due));
    private SoundDetectorSettings settings;
    private SoundEngine engine;
    private ClientLevel level;
    private Vec3 lastBody;
    private boolean active;
    private long dropped, lastChime;
    private String profileServer;
    private record Card(SoundIncident incident, long shown, long expires) { }
    private record AudioCue(long incidentId, String id, float pitch, float volume, long due) { }

    public SoundDetectorController(ClientConfig config) {
        this.config = config;
        instance = this;
        settings = config.soundDetector;
        settings.sanitize();
        engine = new SoundEngine(settings, System::nanoTime);
        configureTags();
        SoundCatalog.rebuild();
    }
    public static SoundDetectorController get() { return instance; }
    public static SoundDetectorController getInstance() { return instance; }
    public SoundDetectorSettings settings() { return settings; }
    public static boolean enabled() {
        return instance != null && instance.config.soundDetector.enabled && Minecraft.getInstance().level != null;
    }
    public static void observe(SoundObservation observation) {
        SoundDetectorController self = instance;
        Minecraft mc = Minecraft.getInstance();
        if (self == null || !enabled() || !mc.isSameThread() || mc.player == null) return;
        self.syncWorld(mc);
        int limit = self.settings.maxObservations;
        if (self.pending.size() >= limit) {
            // Prefer preserving strong new evidence over old low-value ambient observations.
            if (observation.baseScore() == 0) { self.dropped++; return; }
            self.pending.removeFirst(); self.dropped++;
        }
        self.pending.addLast(observation);
    }
    private void syncWorld(Minecraft mc) {
        if (settings != config.soundDetector) {
            settings = config.soundDetector; settings.sanitize();
            engine = new SoundEngine(settings, System::nanoTime);
            configureTags(); resetRuntime();
        }
        if (mc.level != level) {
            resetRuntime(); level = mc.level;
            if (level != null && !Objects.equals(profileServer, serverKey())) {
                profileServer = serverKey();
                if (settings.autoLoadProfiles) settings.loadProfile(profileServer);
            }
            active = settings.enabled && level != null;
        }
    }
    private void configureTags() {
        engine.setBlockTagMatcher((block, tag) -> {
            Identifier blockId = Identifier.tryParse(block), tagId = Identifier.tryParse(tag);
            return blockId != null && tagId != null && BuiltInRegistries.BLOCK.getOptional(blockId)
                    .map(value -> value.builtInRegistryHolder().is(TagKey.create(Registries.BLOCK, tagId))).orElse(false);
        });
    }
    public void tick(Minecraft mc) {
        syncWorld(mc);
        if (!settings.enabled || mc.level == null || mc.player == null) {
            if (active || !pending.isEmpty() || !cards.isEmpty()) resetRuntime();
            return;
        }
        if (!active) { engine.reset(); active = true; }
        Vec3 body = mc.player.position();
        if (lastBody != null && body.distanceToSqr(lastBody) > 128 * 128) {
            pending.clear(); cards.clear(); audio.clear(); engine.reset(); SoundCapture.clear();
        }
        lastBody = body;
        String world = mc.level.dimension().identifier().toString();
        long deadline = System.nanoTime() + 1_000_000;
        for (int processed = 0; processed < 256 && !pending.isEmpty(); processed++) {
            engine.accept(pending.removeFirst(), body.x, body.y, body.z, world);
            if (System.nanoTime() >= deadline) break;
        }
        engine.tick();
        long now = System.nanoTime();
        Map<Long, SoundIncident> live = new HashMap<>();
        for (SoundIncident incident : engine.incidents()) live.put(incident.id(), incident);
        cards.entrySet().removeIf(entry -> entry.getValue().expires() <= now || !live.containsKey(entry.getKey()) || live.get(entry.getKey()).muted());
        cards.replaceAll((id, card) -> {
            SoundIncident latest = live.get(id);
            boolean pinnedEscalation = settings.pinCritical && latest.priority() == SoundIncident.Priority.CRITICAL
                    && card.incident().priority() != SoundIncident.Priority.CRITICAL;
            return new Card(latest, card.shown(), pinnedEscalation ? now + 60_000_000_000L : card.expires());
        });
        for (SoundIncident incident : engine.drainNotifications()) notify(mc, incident, now);
        while (!audio.isEmpty() && audio.peek().due() <= now) {
            AudioCue cue = audio.remove();
            if (!visible(mc) && !settings.audioWhenHidden) continue;
            if (!engine.outputs(cue.incidentId()).chime()) continue;
            Identifier id = Identifier.tryParse(cue.id());
            if (id == null) continue;
            SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getOptional(id).orElseGet(() -> SoundEvent.createVariableRangeEvent(id));
            SoundCapture.withoutCapture(() -> mc.getSoundManager().play(SimpleSoundInstance.forUI(sound, cue.pitch(), cue.volume())));
        }
    }
    private boolean visible(Minecraft mc) {
        return mc.player != null && (mc.screen == null || settings.showInMenus)
                && (!mc.options.hideGui || settings.showWhenHudHidden)
                && (!settings.respectHud || config.showHud || settings.showWhenHudHidden);
    }
    private void notify(Minecraft mc, SoundIncident incident, long now) {
        var outputs = engine.outputs(incident.id());
        boolean visible = visible(mc);
        if (visible && outputs.popup()) {
            long duration = (long) (settings.durationSeconds * 1_000_000_000L);
            if (settings.pinCritical && incident.priority() == SoundIncident.Priority.CRITICAL) duration = 60_000_000_000L;
            Card previous = cards.get(incident.id());
            cards.put(incident.id(), new Card(incident, previous == null ? now : previous.shown(), previous == null ? now + duration : previous.expires()));
            while (cards.size() > settings.maxCards) cards.remove(cards.keySet().iterator().next());
        }
        if (outputs.chat() && visible) mc.gui.getChat().addMessage(Component.literal("[Sound Detector] " + description(incident)));
        if (outputs.chime() && (visible || settings.audioWhenHidden) && now - lastChime >= 2_000_000_000L) {
            lastChime = now;
            String soundId = incident.priority() == SoundIncident.Priority.CRITICAL ? settings.criticalSound : settings.sound;
            for (int repeat = 0; repeat < settings.repeatCount && audio.size() < 32; repeat++)
                audio.add(new AudioCue(incident.id(), soundId, (float) settings.pitch, (float) settings.volume,
                        now + (long) (repeat * settings.repeatIntervalSeconds * 1_000_000_000L)));
        }
    }
    private String description(SoundIncident incident) {
        String actor = incident.actor() == SoundObservation.Actor.PLAYER
                ? hiddenNames() ? "Identified player" : incident.actorName() : "Actor unknown";
        return incident.label() + " · " + actor + " · " + incident.reason();
    }
    public boolean hiddenNames() { return settings.hideNames || config.nameHider.enabled; }
    public List<SoundIncident> history() { return engine.history(); }
    public List<SoundIncident> incidents() { return engine.incidents(); }
    public List<SoundIncident> alerts() {
        if (!settings.enabled || !visible(Minecraft.getInstance())) return List.of();
        var result = new ArrayList<>(cards.values().stream().map(Card::incident).toList());
        if (settings.priorityFirst) result.sort(Comparator.comparing(SoundIncident::priority).reversed());
        return List.copyOf(result);
    }
    public List<SoundIncident> markers() {
        if (!enabled()) return List.of();
        long cutoff = System.nanoTime() - (long) (settings.markerSeconds * 1_000_000_000L);
        return engine.incidents().stream().filter(i -> i.located() && !i.muted() && i.lastNanos() >= cutoff
                && i.priority() != SoundIncident.Priority.INFO && engine.outputs(i.id()).worldMarker()).limit(128).toList();
    }
    public long shownAt(long id) { Card card = cards.get(id); return card == null ? System.nanoTime() : card.shown(); }
    public long expiresAt(long id) { Card card = cards.get(id); return card == null ? Long.MAX_VALUE : card.expires(); }
    public long droppedObservations() { return dropped + engine.droppedObservations(); }
    public void acknowledge() { alerts().stream().findFirst().ifPresent(i -> acknowledge(i.id())); }
    public void acknowledge(long id) { cards.remove(id); audio.removeIf(cue -> cue.incidentId() == id); engine.acknowledge(id); }
    public void muteSource(long id, double seconds) { cards.remove(id); audio.removeIf(cue -> cue.incidentId() == id); engine.muteSource(id, seconds); }
    public void mute(double seconds) { cards.clear(); audio.clear(); engine.mute(seconds); }
    public void clearHistory() { engine.clearHistory(); }
    public String serverKey() {
        var server = Minecraft.getInstance().getCurrentServer();
        return SoundDetectorSettings.normalizeServer(server == null ? "singleplayer" : server.ip);
    }
    public void saveProfile() { settings.saveProfile(serverKey()); }
    public boolean loadProfile() { boolean loaded = settings.loadProfile(serverKey()); if (loaded) { engine.reset(); cards.clear(); audio.clear(); pending.clear(); } return loaded; }
    public void disconnect() {
        resetRuntime(); profileServer = null;
    }
    private void resetRuntime() {
        pending.clear(); cards.clear(); audio.clear(); engine.reset(); SoundCapture.clear();
        level = null; lastBody = null; active = false; lastChime = 0;
    }
    public Path exportHistory() throws IOException {
        Path directory = Minecraft.getInstance().gameDirectory.toPath().resolve("sound-detector");
        Files.createDirectories(directory);
        Path output = directory.resolve("history-" + System.currentTimeMillis() + ".json");
        // Export copies are redacted consistently with the visible history/card privacy settings.
        List<Map<String, Object>> redacted = history().stream().map(i -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", i.id()); row.put("action", i.actionKey()); row.put("label", i.label()); row.put("category", i.category());
            row.put("block", i.blockId()); row.put("sound", i.soundId()); row.put("attribution", i.actor());
            if (!hiddenNames()) { row.put("actorUuid", i.actorUuid()); row.put("actorName", i.actorName()); }
            if (!settings.hideCoordinates && i.located()) row.put("position", List.of(i.x(), i.y(), i.z()));
            row.put("count", i.count()); row.put("score", i.score()); row.put("priority", i.priority()); row.put("reason", i.reason());
            row.put("firstNanos", i.firstNanos()); row.put("lastNanos", i.lastNanos());
            return row;
        }).toList();
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(Map.of(
                "formatVersion", 1, "incidents", redacted, "droppedObservations", droppedObservations())), StandardCharsets.UTF_8);
        return output;
    }
    public Path exportCatalog() throws IOException {
        Path output = Minecraft.getInstance().gameDirectory.toPath().resolve("sound-detector/block-coverage.json");
        SoundCatalog.exportCoverage(output); return output;
    }
}
