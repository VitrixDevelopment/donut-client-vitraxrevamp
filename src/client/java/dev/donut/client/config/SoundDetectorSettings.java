package dev.donut.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import java.lang.reflect.Modifier;
import java.util.*;

/** Persistent controls. Runtime observations, cooldowns and temporary mutes are never saved here. */
public final class SoundDetectorSettings {
    public static final List<String> PRESETS = List.of("Balanced activity", "Players only", "Base activity", "Diagnostics");
    public static final List<String> CATEGORIES = List.of("containers", "workstations", "building", "switches", "machinery", "music", "movement", "combat", "transport", "explosions", "farming", "sculk", "natural", "custom");
    private static final Gson PROFILE_GSON = new GsonBuilder().addSerializationExclusionStrategy(new ExclusionStrategy() {
        public boolean shouldSkipField(FieldAttributes f) { return f.getDeclaringClass() == SoundDetectorSettings.class && f.getName().equals("profiles"); }
        public boolean shouldSkipClass(Class<?> type) { return false; }
    }).create();

    public boolean enabled, favorite;
    public int settingsVersion = 1;
    public String preset = "Balanced activity";
    public double range = 128, minRange = 0, minHeight = -2048, maxHeight = 2048;
    public boolean horizontalOnly, playersOnly, includeClientDerived;
    public boolean suppressSelf = true, suppressProbableSelf = true, ignoreNonPlayers = true;
    public double selfWindowSeconds = 1, evidenceSeconds = 10, cooldownSeconds = 5, warmupSeconds = 2;
    public double groupingHorizontal = 4, groupingVertical = 8;
    public int warningThreshold = 50, criticalThreshold = 85, dedupeMillis = 150;
    public int historyLimit = 500, maxIncidents = 512, maxObservations = 4096;
    public Map<String, Boolean> categories = new LinkedHashMap<>();
    public List<String> trustedUuids = new ArrayList<>();
    public List<Rule> rules = new ArrayList<>();
    public List<Zone> zones = new ArrayList<>();
    public Map<String, Profile> profiles = new LinkedHashMap<>();
    public boolean autoLoadProfiles = true;

    public boolean popup = true, chime = true, chat, worldMarkers;
    public boolean showInMenus, audioWhenHidden, showWhenHudHidden, respectHud = true;
    public boolean priorityFirst = true, compact, animation = true, shadow = true;
    public boolean showActor = true, showCoordinates, showDirection = true, showDistance = true;
    public boolean showHeight = true, showCount = true, showReason = true, showAge = true;
    public boolean hideNames, hideCoordinates, pinCritical;
    public String material = "Glass", sound = "minecraft:block.note_block.pling", criticalSound = "minecraft:block.note_block.bell";
    public String titleTemplate = "{event}", detailTemplate = "{actor}";
    public double x = .5, y = .06, scale = 1, opacity = .94, textOpacity = 1, borderOpacity = .8;
    public double fontScale = 1;
    public double durationSeconds = 6, markerSeconds = 30, volume = .35, pitch = 1;
    public int repeatCount = 1, fadeInMillis = 180, fadeOutMillis = 180, slidePixels = 16;
    public double repeatIntervalSeconds = .5;
    public int width = 320, padding = 10, radius = 8, borderThickness = 1, maxCards = 3;
    public int topColor = 0x292C3B, bottomColor = 0x171A21, textColor = 0xF2F4F8;
    public int borderColor = 0x596378, warningColor = 0xFFC66B, criticalColor = 0xFF707B;

    public SoundDetectorSettings() { for (String category : CATEGORIES) categories.put(category, !category.equals("natural") && !category.equals("custom")); }

    public boolean categoryEnabled(String id) { return id != null && CATEGORIES.contains(id) && !Boolean.FALSE.equals(categories == null ? null : categories.get(id)); }

    /** Presets change detection only, preserving appearance and user rules. */
    public void applyPreset(String value) {
        preset = value != null && PRESETS.contains(value) ? value : PRESETS.getFirst();
        playersOnly = preset.equals("Players only");
        warningThreshold = 50; criticalThreshold = 85;
        includeClientDerived = false; suppressSelf = true; suppressProbableSelf = true; ignoreNonPlayers = true;
        categories = new LinkedHashMap<>();
        for (String category : CATEGORIES) categories.put(category, !category.equals("natural") && !category.equals("custom"));
        if (preset.equals("Diagnostics")) { for (String category : CATEGORIES) categories.put(category, true); includeClientDerived = true; ignoreNonPlayers = false; }
    }

    public void sanitize() { sanitize(true); }
    private void sanitize(boolean allowProfiles) {
        settingsVersion = 1;
        if (preset == null || !PRESETS.contains(preset)) preset = PRESETS.getFirst();
        range = finite(range, 0, 512, 128); minRange = finite(minRange, 0, range, 0);
        minHeight = finite(minHeight, -4096, 4096, -2048); maxHeight = finite(maxHeight, minHeight, 4096, 2048);
        selfWindowSeconds = finite(selfWindowSeconds, .25, 3, 1);
        evidenceSeconds = finite(evidenceSeconds, 1, 60, 10); cooldownSeconds = finite(cooldownSeconds, .1, 300, 5);
        warmupSeconds = finite(warmupSeconds, 0, 10, 2); dedupeMillis = Math.clamp(dedupeMillis, 0, 1000);
        groupingHorizontal = finite(groupingHorizontal, 1, 32, 4); groupingVertical = finite(groupingVertical, 1, 64, 8);
        warningThreshold = Math.clamp(warningThreshold, 1, 100); criticalThreshold = Math.clamp(criticalThreshold, warningThreshold, 100);
        historyLimit = Math.clamp(historyLimit, 1, 2000); maxIncidents = Math.clamp(maxIncidents, 16, 512); maxObservations = Math.clamp(maxObservations, 64, 4096);
        if (categories == null) categories = new LinkedHashMap<>();
        categories.keySet().removeIf(key -> key == null || !CATEGORIES.contains(key));
        for (String category : CATEGORIES) { if (categories.get(category) == null) categories.put(category, !category.equals("natural") && !category.equals("custom")); }
        trustedUuids = cleanStrings(trustedUuids, 128, 64);
        rules = bounded(rules, 64); rules.forEach(Rule::sanitize);
        zones = bounded(zones, 64); zones.forEach(Zone::sanitize);
        x = finite(x, 0, 1, .5); y = finite(y, 0, 1, .06); scale = finite(scale, .5, 2, 1);
        fontScale = finite(fontScale, .75, 1.5, 1);
        opacity = finite(opacity, .05, 1, .94); textOpacity = finite(textOpacity, .1, 1, 1); borderOpacity = finite(borderOpacity, 0, 1, .8);
        durationSeconds = finite(durationSeconds, 1, 60, 6); markerSeconds = finite(markerSeconds, 1, 120, 30);
        volume = finite(volume, 0, 1, .35); pitch = finite(pitch, .5, 2, 1);
        repeatCount = Math.clamp(repeatCount, 1, 3); repeatIntervalSeconds = finite(repeatIntervalSeconds, .25, 2, .5);
        fadeInMillis = Math.clamp(fadeInMillis, 0, 2000); fadeOutMillis = Math.clamp(fadeOutMillis, 0, 2000); slidePixels = Math.clamp(slidePixels, 0, 80);
        width = Math.clamp(width, 160, 640); padding = Math.clamp(padding, 4, 24); radius = Math.clamp(radius, 0, 24);
        borderThickness = Math.clamp(borderThickness, 0, 4); maxCards = Math.clamp(maxCards, 1, 5);
        if (material == null || !List.of("Glass", "Solid", "Minimal").contains(material)) material = "Glass";
        sound = text(sound, 128, "minecraft:block.note_block.pling"); criticalSound = text(criticalSound, 128, "minecraft:block.note_block.bell");
        titleTemplate = text(titleTemplate, 160, "{event}"); detailTemplate = text(detailTemplate, 240, "{actor}");
        topColor &= 0xFFFFFF; bottomColor &= 0xFFFFFF; textColor &= 0xFFFFFF;
        borderColor &= 0xFFFFFF; warningColor &= 0xFFFFFF; criticalColor &= 0xFFFFFF;
        if (profiles == null || !allowProfiles) profiles = new LinkedHashMap<>();
        else {
            Map<String,Profile> valid = new LinkedHashMap<>();
            for (var entry : profiles.entrySet()) {
                if (valid.size() == 32) break;
                if (entry.getKey() == null || entry.getValue() == null || entry.getValue().settings == null) continue;
                Profile p = entry.getValue(); p.name = text(p.name, 80, normalizeServer(entry.getKey())); p.settings.sanitize(false);
                valid.put(normalizeServer(entry.getKey()), p);
            }
            profiles = valid;
        }
    }

    public SoundDetectorSettings copyWithoutProfiles() {
        SoundDetectorSettings copy = PROFILE_GSON.fromJson(PROFILE_GSON.toJson(this), SoundDetectorSettings.class);
        copy.sanitize(false); return copy;
    }
    public void saveProfile(String serverKey) {
        if (profiles == null) profiles = new LinkedHashMap<>();
        String key = normalizeServer(serverKey);
        if (!profiles.containsKey(key) && profiles.size() >= 32) return;
        Profile profile = new Profile(); profile.name = key; profile.settings = copyWithoutProfiles(); profiles.put(key, profile);
    }
    public boolean loadProfile(String serverKey) {
        Profile profile = profiles == null ? null : profiles.get(normalizeServer(serverKey));
        if (profile == null || profile.settings == null) return false;
        SoundDetectorSettings copy = profile.settings.copyWithoutProfiles();
        try {
            for (var field : SoundDetectorSettings.class.getFields()) {
                if (Modifier.isStatic(field.getModifiers()) || List.of("enabled", "favorite", "profiles").contains(field.getName())) continue;
                field.set(this, field.get(copy));
            }
        } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        return true;
    }
    public static String normalizeServer(String value) { return text(value, 255, "singleplayer").trim().toLowerCase(Locale.ROOT); }

    public static final class Profile { public String name = "Profile"; public SoundDetectorSettings settings; }
    public static final class Zone {
        public String name = "Expected area", dimension = "";
        public double x, y, z, radius = 16;
        public boolean exclude, mute = true;
        public void sanitize() { name = text(name, 80, "Expected area"); dimension = text(dimension, 128, ""); x = finite(x,-30_000_000,30_000_000,0); y = finite(y,-4096,4096,0); z = finite(z,-30_000_000,30_000_000,0); radius = finite(radius,1,512,16); }
        public boolean contains(double px, double py, double pz, String world) { return (dimension.isEmpty() || dimension.equals(world)) && (px-x)*(px-x)+(py-y)*(py-y)+(pz-z)*(pz-z) <= radius*radius; }
    }
    public static final class Rule {
        public String name = "Custom rule", category = "", soundPattern = "", blockPattern = "", actor = "", route = "", dimension = "", playerUuid = "", priority = "WARNING";
        public boolean enabled = true, exclude, popup = true, chime = true, chat, worldMarker, novelOnly;
        public int minCount = 1, minFamilies = 1, minScore = 0;
        public double minDistance = 0, maxDistance = 512, minHeight = -4096, maxHeight = 4096, windowSeconds = 10, cooldownSeconds = 5;
        public ConditionGroup match;
        public void sanitize() {
            name=text(name,80,"Custom rule"); category=text(category,32,""); soundPattern=text(soundPattern,128,""); blockPattern=text(blockPattern,128,""); actor=text(actor,32,""); route=text(route,32,""); dimension=text(dimension,128,""); playerUuid=text(playerUuid,64,"");
            if (priority == null || !List.of("INFO","WARNING","CRITICAL").contains(priority)) priority="WARNING";
            minCount=Math.clamp(minCount,1,1000); minFamilies=Math.clamp(minFamilies,1,14); minScore=Math.clamp(minScore,0,100);
            minDistance=finite(minDistance,0,512,0); maxDistance=finite(maxDistance,minDistance,512,512); minHeight=finite(minHeight,-4096,4096,-4096); maxHeight=finite(maxHeight,minHeight,4096,4096);
            windowSeconds=finite(windowSeconds, .1,60,10); cooldownSeconds=finite(cooldownSeconds,.1,300,5);
            if (match != null) match.sanitize(0);
        }
    }
    public static final class ConditionGroup {
        public String operation = "ALL";
        public List<Condition> conditions = new ArrayList<>();
        public List<ConditionGroup> groups = new ArrayList<>();
        private void sanitize(int depth) {
            if (operation == null || !List.of("ALL","ANY").contains(operation)) operation="ALL";
            conditions=bounded(conditions,16); conditions.forEach(Condition::sanitize);
            groups=depth >= 3 ? new ArrayList<>() : bounded(groups,4); groups.forEach(g -> g.sanitize(depth+1));
        }
    }
    public static final class Condition {
        public String field = "category", op = "EQUALS", value = "containers";
        public void sanitize() { field=text(field,32,"category"); op=text(op,16,"EQUALS"); value=text(value,128,""); }
    }
    private static <T> List<T> bounded(List<T> list,int max) { return list == null ? new ArrayList<>() : new ArrayList<>(list.stream().filter(Objects::nonNull).limit(max).toList()); }
    private static List<String> cleanStrings(List<String> list,int max,int length) { return bounded(list,max).stream().map(v -> text(v,length,"")).filter(v -> !v.isEmpty()).distinct().collect(java.util.stream.Collectors.toCollection(ArrayList::new)); }
    public static double finite(double value,double min,double max,double fallback) { return Double.isFinite(value) ? Math.clamp(value,min,max) : Math.clamp(fallback,min,max); }
    public static String text(String value,int length,String fallback) { return value == null ? fallback : value.substring(0,Math.min(length,value.length())).replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", ""); }
}
