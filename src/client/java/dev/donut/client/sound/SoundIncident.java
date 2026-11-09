package dev.donut.client.sound;

import java.util.List;

/** Immutable presentation snapshot. Scores are points, never player-presence probabilities. */
public record SoundIncident(long id, String actionKey, String label, String category, String blockId, String soundId,
        double x,double y,double z,String actorUuid,String actorName,SoundObservation.Actor actor,boolean probableSelf,
        int count,int score,Priority priority,String reason,long firstNanos,long lastNanos,boolean muted,List<SoundObservation> evidence) {
    public enum Priority { INFO, WARNING, CRITICAL }
    public SoundIncident { evidence=List.copyOf(evidence); }
    public boolean located() { return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && evidence.stream().anyMatch(SoundObservation::located); }
}
