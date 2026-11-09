package dev.donut.client.sound;

/** An immutable observed event. Unknown/global locations never become guessed coordinates. */
public record SoundObservation(long timeNanos, Route route, String soundId, String category, String actionKey,
        String label, String blockId, double x, double y, double z, int entityId, String actorUuid,
        String actorName, Actor actor, float volume, float pitch, long seed, int eventCode, int data,
        boolean probableSelf, int baseScore, boolean global, String soundSource) {
    public enum Route { POSITIONAL, ENTITY_SOUND, BLOCK_EVENT, LEVEL_EVENT, ENTITY_EVENT, EXPLOSION, MINING, DAMAGE, CLIENT_DERIVED, STOP_SOUND }
    public enum Actor { SELF, PLAYER, NON_PLAYER, UNKNOWN }
    public SoundObservation {
        route = route == null ? Route.POSITIONAL : route;
        soundId = clean(soundId); category = clean(category); actionKey = clean(actionKey); label = clean(label); blockId = clean(blockId); actorUuid = clean(actorUuid); actorName = clean(actorName); soundSource=clean(soundSource);
        actor = actor == null ? Actor.UNKNOWN : actor;
        baseScore = Math.clamp(baseScore,0,100);
        // A remote player attribution needs stable identity, not just a reused numeric ID.
        if (actor == Actor.PLAYER && actorUuid.isEmpty()) { actor=Actor.UNKNOWN; actorName=""; }
        if (actor != Actor.PLAYER && actor != Actor.SELF) { actorName=""; actorUuid=""; }
    }
    public SoundObservation(long timeNanos,Route route,String soundId,String category,String actionKey,String label,String blockId,double x,double y,double z,int entityId,String actorUuid,String actorName,Actor actor,float volume,float pitch,long seed,int eventCode,int data,boolean probableSelf,int baseScore) {
        this(timeNanos,route,soundId,category,actionKey,label,blockId,x,y,z,entityId,actorUuid,actorName,actor,volume,pitch,seed,eventCode,data,probableSelf,baseScore,false,"");
    }
    public SoundObservation(long timeNanos,Route route,String soundId,String category,String actionKey,String label,String blockId,double x,double y,double z,int entityId,String actorUuid,String actorName,Actor actor,float volume,float pitch,long seed,int eventCode,int data,boolean probableSelf,int baseScore,boolean global) {
        this(timeNanos,route,soundId,category,actionKey,label,blockId,x,y,z,entityId,actorUuid,actorName,actor,volume,pitch,seed,eventCode,data,probableSelf,baseScore,global,"");
    }
    private static String clean(String value) { return value == null ? "" : value.substring(0,Math.min(256,value.length())).replaceAll("[\\p{Cntrl}]", ""); }
    public boolean located() { return !global && Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z); }
    public SoundObservation withProbableSelf(boolean value) { return new SoundObservation(timeNanos,route,soundId,category,actionKey,label,blockId,x,y,z,entityId,actorUuid,actorName,actor,volume,pitch,seed,eventCode,data,value,baseScore,global,soundSource); }
    public SoundObservation withActor(Actor value,String uuid,String name) { return new SoundObservation(timeNanos,route,soundId,category,actionKey,label,blockId,x,y,z,entityId,uuid,name,value,volume,pitch,seed,eventCode,data,probableSelf,baseScore,global,soundSource); }
    public SoundObservation withSoundSource(String value) { return new SoundObservation(timeNanos,route,soundId,category,actionKey,label,blockId,x,y,z,entityId,actorUuid,actorName,actor,volume,pitch,seed,eventCode,data,probableSelf,baseScore,global,value); }
}
