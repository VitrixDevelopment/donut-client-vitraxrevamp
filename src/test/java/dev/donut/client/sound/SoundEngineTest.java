package dev.donut.client.sound;

import dev.donut.client.config.SoundDetectorSettings;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.donut.client.sound.SoundObservation.*;

class SoundEngineTest {
    private final AtomicLong clock=new AtomicLong(100_000_000_000L);
    private final SoundDetectorSettings settings=settings();
    private final SoundEngine engine=new SoundEngine(settings,clock::get);
    private static SoundDetectorSettings settings() {var s=new SoundDetectorSettings();s.enabled=true;s.warmupSeconds=0;s.dedupeMillis=0;s.sanitize();return s;}
    private SoundObservation event(Route route,String category,String action,int score,double x,double y,double z) {
        return new SoundObservation(clock.get(),route,"minecraft:block.chest.open",category,action,"Chest opened","",x,y,z,-1,"","",Actor.UNKNOWN,1,1,42,1,0,false,score);
    }
    private SoundObservation chest(Route route,double x) {return event(route,"containers","container.open",50,x,64,0);}
    private void accept(SoundObservation e) {engine.accept(e,0,64,0,"minecraft:overworld");}
    private void advance(double seconds) {clock.addAndGet((long)(seconds*1_000_000_000));engine.tick();}

    @Test void unknownContainerWarnsWithoutInventingActor() {
        accept(chest(Route.POSITIONAL,4));
        var alert=engine.drainNotifications().getFirst();
        assertEquals(Actor.UNKNOWN,alert.actor());assertEquals("",alert.actorName());assertEquals(50,alert.score());assertEquals(SoundIncident.Priority.WARNING,alert.priority());
    }
    @Test void crossRouteDuplicatesMergeButSameRouteRepeatsRemainIndependent() {
        settings.dedupeMillis=150;
        accept(chest(Route.POSITIONAL,4));accept(chest(Route.BLOCK_EVENT,4));
        assertEquals(1,engine.incidents().getFirst().count());
        assertTrue(engine.drainNotifications().isEmpty());
        accept(chest(Route.POSITIONAL,4));
        assertEquals(2,engine.incidents().getFirst().count());advance(.16);
        assertEquals(1,engine.drainNotifications().size());
    }
    @Test void adjacentIndependentBlocksDoNotMergeAcrossRoutes() {
        settings.dedupeMillis=150;
        accept(chest(Route.POSITIONAL,4));accept(chest(Route.BLOCK_EVENT,5));
        assertEquals(2,engine.incidents().getFirst().count());
    }
    @Test void nearbyActorsNeverBecomeAnIdentityAndDifferentPlayersRemainSeparate() {
        accept(chest(Route.POSITIONAL,4));
        accept(chest(Route.ENTITY_SOUND,4).withActor(Actor.PLAYER,"player-a","Alex"));
        accept(chest(Route.ENTITY_SOUND,4).withActor(Actor.PLAYER,"player-b","Blair"));
        assertEquals(3,engine.incidents().size());
        assertEquals(1,engine.incidents().stream().filter(i->i.actor()==Actor.UNKNOWN).count());
    }
    @Test void remoteIdentityCanUpgradeCrossRouteEvidenceWithoutDoubleCounting() {
        settings.dedupeMillis=150;
        accept(chest(Route.POSITIONAL,4));accept(chest(Route.ENTITY_SOUND,4).withActor(Actor.PLAYER,"player-a","Alex"));
        var incident=engine.incidents().getFirst();assertEquals(1,incident.count());assertEquals("Alex",incident.actorName());assertEquals(70,incident.score());
    }
    @Test void explicitNonPlayerOrSelfEvidenceCancelsDeferredUnknownWarning() {
        settings.dedupeMillis=150;
        accept(chest(Route.POSITIONAL,4));accept(chest(Route.ENTITY_SOUND,4).withActor(Actor.NON_PLAYER,"",""));advance(.2);
        assertTrue(engine.drainNotifications().isEmpty());assertTrue(engine.incidents().isEmpty());
        accept(chest(Route.POSITIONAL,8));accept(chest(Route.ENTITY_SOUND,8).withActor(Actor.SELF,"self","Me"));advance(.2);
        assertTrue(engine.drainNotifications().isEmpty());
    }
    @Test void localEvidenceNeverOverridesDirectOtherPlayer() {
        accept(chest(Route.ENTITY_SOUND,4).withActor(Actor.PLAYER,"player-a","Alex").withProbableSelf(true));
        assertEquals("Alex",engine.drainNotifications().getFirst().actorName());
        accept(chest(Route.POSITIONAL,8).withProbableSelf(true));assertEquals(1,engine.incidents().size());
    }
    @Test void explicitActorWinsOverUnknownBlockMetadata() {
        settings.dedupeMillis=150;settings.ignoreNonPlayers=false;
        accept(chest(Route.ENTITY_SOUND,4).withActor(Actor.NON_PLAYER,"",""));
        var b=chest(Route.BLOCK_EVENT,4);
        accept(new SoundObservation(b.timeNanos(),b.route(),b.soundId(),b.category(),b.actionKey(),b.label(),"minecraft:chest",b.x(),b.y(),b.z(),b.entityId(),b.actorUuid(),b.actorName(),b.actor(),b.volume(),b.pitch(),b.seed(),b.eventCode(),b.data(),false,b.baseScore()));
        assertEquals(Actor.NON_PLAYER,engine.incidents().getFirst().actor());
    }
    @Test void playerProfileRequiresIdentifiedSupportedPlayerEvent() {
        settings.applyPreset("Players only");
        accept(chest(Route.POSITIONAL,4));assertTrue(engine.history().isEmpty());
        accept(event(Route.ENTITY_EVENT,"combat","unknown.status",0,4,64,0).withActor(Actor.PLAYER,"player-a","Alex"));
        assertTrue(engine.drainNotifications().isEmpty());
        accept(chest(Route.ENTITY_SOUND,4).withActor(Actor.PLAYER,"player-a","Alex"));assertEquals(1,engine.drainNotifications().size());
    }
    @Test void unknownPlayerStatusDoesNotAcquireSeventyPoints() {
        accept(event(Route.ENTITY_EVENT,"combat","unknown.status",0,4,64,0).withActor(Actor.PLAYER,"player-a","Alex"));
        assertEquals(0,engine.history().getFirst().score());assertTrue(engine.drainNotifications().isEmpty());
    }
    @Test void unsupportedPlayerStatusesDoNotAddScoreOrReplaceAValidActionLabel() {
        settings.categories.put("custom",true);
        accept(chest(Route.ENTITY_SOUND,4).withActor(Actor.PLAYER,"player-a","Alex"));engine.drainNotifications();
        for(int i=0;i<20;i++) accept(event(Route.ENTITY_EVENT,"custom","unknown.status",0,4,64,0).withActor(Actor.PLAYER,"player-a","Alex"));
        assertEquals(70,engine.incidents().getFirst().score());assertEquals("container.open",engine.incidents().getFirst().actionKey());
        assertTrue(engine.drainNotifications().isEmpty());
    }
    @Test void rangeUsesVerticalDistanceUnlessHorizontalModeIsChosen() {
        settings.range=16;accept(event(Route.POSITIONAL,"containers","container.open",50,0,84,0));assertTrue(engine.history().isEmpty());
        settings.horizontalOnly=true;accept(event(Route.POSITIONAL,"containers","container.open",50,0,84,0));assertEquals(1,engine.history().size());
    }
    @Test void negativeCoordinatesUseFloorBuckets() {
        accept(chest(Route.POSITIONAL,-.1));accept(chest(Route.POSITIONAL,.1));assertEquals(2,engine.incidents().size());
    }
    @Test void configuredGroupingControlsHorizontalBucketsAndVerticalTolerance() {
        settings.groupingHorizontal=8;settings.groupingVertical=2;
        accept(chest(Route.POSITIONAL,3));accept(chest(Route.POSITIONAL,5));assertEquals(1,engine.incidents().size());
        accept(event(Route.POSITIONAL,"containers","container.open",50,5,67,0));assertEquals(2,engine.incidents().size());
        accept(chest(Route.POSITIONAL,9));assertEquals(3,engine.incidents().size());
    }
    @Test void blockTagRulesRequireKnownBlockAndExplicitResolver() {
        settings.warningThreshold=100;var rule=new SoundDetectorSettings.Rule();rule.blockPattern="#minecraft:storage_blocks";settings.rules.add(rule);
        var original=chest(Route.BLOCK_EVENT,4);
        var known=new SoundObservation(original.timeNanos(),original.route(),original.soundId(),original.category(),original.actionKey(),original.label(),"minecraft:chest",original.x(),original.y(),original.z(),-1,"","",Actor.UNKNOWN,1,1,0,1,0,false,50);
        accept(known);assertTrue(engine.drainNotifications().isEmpty());
        engine.setBlockTagMatcher((block,tag)->block.equals("minecraft:chest")&&tag.equals("minecraft:storage_blocks"));
        accept(known);assertEquals(1,engine.drainNotifications().size());
        engine.reset();accept(chest(Route.BLOCK_EVENT,4));assertTrue(engine.drainNotifications().isEmpty());
        engine.setBlockTagMatcher(null);accept(known);assertTrue(engine.drainNotifications().isEmpty());
    }
    @Test void machineryCannotAccumulateHumanCertaintyButBasePresetCanWarn() {
        for(int i=0;i<20;i++) accept(event(Route.BLOCK_EVENT,"machinery","piston.extend",15,4,64,0));
        assertTrue(engine.drainNotifications().isEmpty());assertTrue(engine.history().getFirst().score()<=35);
        settings.applyPreset("Base activity");accept(event(Route.BLOCK_EVENT,"machinery","piston.retract",15,4,64,0));
        assertEquals(Actor.UNKNOWN,engine.drainNotifications().getFirst().actor());
    }
    @Test void diagnosticsRecordsWithoutDefaultOutputs() {
        settings.applyPreset("Diagnostics");accept(chest(Route.POSITIONAL,4));
        assertEquals(1,engine.history().size());assertTrue(engine.drainNotifications().isEmpty());
    }
    @Test void rulesCanMatchNonPrimaryEvidenceAndHaveIndependentOutputs() {
        settings.dedupeMillis=150;settings.warningThreshold=100;
        var rule=new SoundDetectorSettings.Rule();rule.name="Sound only";rule.soundPattern="minecraft:block.chest.*";rule.route="POSITIONAL";rule.popup=false;rule.chime=false;rule.chat=true;
        settings.rules.add(rule);
        accept(chest(Route.POSITIONAL,4));accept(chest(Route.BLOCK_EVENT,4));advance(.2);
        var notification=engine.drainNotifications().getFirst();var outputs=engine.outputs(notification.id());
        assertTrue(outputs.chat());assertFalse(outputs.popup());assertFalse(outputs.chime());assertTrue(notification.reason().contains("Sound only"));
    }
    @Test void ruleCountsOnlyMatchingLogicalEventsWithinItsOwnWindow() {
        settings.warningThreshold=100;
        var rule=new SoundDetectorSettings.Rule();rule.soundPattern="minecraft:block.chest.open";rule.minCount=3;rule.windowSeconds=1;settings.rules.add(rule);
        accept(chest(Route.POSITIONAL,4));advance(.6);accept(chest(Route.POSITIONAL,4));advance(.6);accept(chest(Route.POSITIONAL,4));
        assertTrue(engine.drainNotifications().isEmpty());accept(chest(Route.POSITIONAL,4));assertEquals(1,engine.drainNotifications().size());
    }
    @Test void typedRulesSupportBoundedConditionsAndMuteZonesWin() {
        settings.warningThreshold=100;var rule=new SoundDetectorSettings.Rule();rule.match=new SoundDetectorSettings.ConditionGroup();
        var condition=new SoundDetectorSettings.Condition();condition.field="distance";condition.op="AT_MOST";condition.value="8";rule.match.conditions.add(condition);settings.rules.add(rule);
        var zone=new SoundDetectorSettings.Zone();zone.x=4;zone.y=64;zone.radius=2;settings.zones.add(zone);
        accept(chest(Route.POSITIONAL,4));assertTrue(engine.drainNotifications().isEmpty());
        accept(chest(Route.POSITIONAL,8));assertEquals(1,engine.drainNotifications().size());
    }
    @Test void globalEffectsNeverGetLocalCoordinatesOrAutomaticScores() {
        var e=chest(Route.LEVEL_EVENT,4);
        accept(new SoundObservation(e.timeNanos(),e.route(),e.soundId(),e.category(),e.actionKey(),e.label(),e.blockId(),e.x(),e.y(),e.z(),-1,"","",Actor.UNKNOWN,1,1,0,1,0,false,50,true));
        assertFalse(engine.history().getFirst().located());assertEquals(0,engine.history().getFirst().score());assertTrue(engine.drainNotifications().isEmpty());
    }
    @Test void repeatedEventsUpdateWithoutRepeatedNotificationsAndQuietPeriodRearms() {
        accept(chest(Route.POSITIONAL,4));assertEquals(1,engine.drainNotifications().size());
        for(int i=0;i<6;i++){advance(2);accept(chest(Route.POSITIONAL,4));}
        assertTrue(engine.drainNotifications().isEmpty());advance(11);accept(chest(Route.POSITIONAL,4));assertEquals(1,engine.drainNotifications().size());
    }
    @Test void sourceMuteExpiresAndResetDropsAllPendingHistoryAndNotifications() {
        accept(chest(Route.POSITIONAL,4));long id=engine.drainNotifications().getFirst().id();engine.muteSource(id,1);
        accept(chest(Route.POSITIONAL,4));assertTrue(engine.drainNotifications().isEmpty());advance(1.1);
        accept(chest(Route.POSITIONAL,4));assertEquals(1,engine.drainNotifications().size());
        engine.reset();assertTrue(engine.history().isEmpty());assertTrue(engine.incidents().isEmpty());assertTrue(engine.drainNotifications().isEmpty());
    }
    @Test void muteExpiryRestoresOutputsWithoutNeedingAnotherObservation() {
        accept(chest(Route.POSITIONAL,4));long id=engine.history().getFirst().id();engine.mute(1);engine.tick();assertFalse(engine.outputs(id).popup());
        advance(1.1);assertTrue(engine.outputs(id).popup());
    }
    @Test void clearHistoryDoesNotEraseCurrentIncidentsAndExpiryPreservesHistoricEvidence() {
        accept(chest(Route.POSITIONAL,4));advance(11);assertEquals(0,engine.incidents().getFirst().count());
        assertEquals(1,engine.history().getFirst().count());assertFalse(engine.history().getFirst().evidence().isEmpty());
        engine.clearHistory();assertTrue(engine.history().isEmpty());assertFalse(engine.incidents().isEmpty());advance(31);assertTrue(engine.incidents().isEmpty());
    }
    @Test void captureStormIsBoundedAndGlobalNotificationRateIsLimited() {
        settings.maxIncidents=16;settings.historyLimit=20;settings.maxObservations=64;
        for(int i=0;i<200;i++) accept(event(Route.POSITIONAL,"containers","container.open",50,(i%20)*4,64,(i/20)*4));
        assertTrue(engine.incidents().size()<=16);assertTrue(engine.history().size()<=20);assertTrue(engine.droppedObservations()>0);
        assertTrue(engine.drainNotifications().size()<=3);
    }
    @Test void boundedWildcardUsesNoRegexSemantics() {
        assertTrue(SoundEngine.glob("minecraft:block.*.open","minecraft:block.chest.open"));
        assertFalse(SoundEngine.glob("minecraft:block.[a-z]+.open","minecraft:block.chest.open"));
        assertFalse(SoundEngine.glob("x".repeat(129),"x"));
    }
    @Test void stoppingMusicRemovesItsActiveMarkerButRetainsTheObservedHistory() {
        accept(event(Route.POSITIONAL,"music","jukebox.play",30,4,64,0).withSoundSource("record"));
        var stop=new SoundObservation(clock.get(),Route.STOP_SOUND,"minecraft:block.chest.open","custom","sound.stop","Sound stopped","",Double.NaN,Double.NaN,Double.NaN,-1,"","",Actor.UNKNOWN,0,0,0,0,0,false,0).withSoundSource("record");
        accept(stop);assertTrue(engine.incidents().isEmpty());assertEquals(1,engine.history().size());
    }
}
