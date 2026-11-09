package dev.donut.client.sound;

import dev.donut.client.config.SoundDetectorSettings;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.BiPredicate;
import static dev.donut.client.sound.SoundObservation.Actor;
import static dev.donut.client.sound.SoundIncident.Priority;

/** Bounded, deterministic incident and rule processing. No game state, audio or rendering dependencies. */
public final class SoundEngine {
    public record Outputs(boolean popup,boolean chime,boolean chat,boolean worldMarker) {
        public static final Outputs NONE = new Outputs(false,false,false,false);
        Outputs merge(Outputs other) { return new Outputs(popup||other.popup,chime||other.chime,chat||other.chat,worldMarker||other.worldMarker); }
        boolean any() { return popup||chime||chat||worldMarker; }
    }
    private final SoundDetectorSettings settings;
    private final LongSupplier clock;
    private final LinkedHashMap<Long,Incident> active = new LinkedHashMap<>();
    private final LinkedHashMap<Long,SoundIncident> history = new LinkedHashMap<>();
    private final LinkedHashMap<Long,Logical> logicals = new LinkedHashMap<>();
    private final ArrayDeque<Long> pending = new ArrayDeque<>();
    private final ArrayDeque<SoundIncident> notifications = new ArrayDeque<>();
    private final ArrayDeque<Long> delivered = new ArrayDeque<>();
    private final Map<Long,Long> sourceMutes = new HashMap<>();
    private long nextId=1, nextLogical=1, started, mutedUntil, dropped;
    private double bodyX,bodyY,bodyZ;
    private BiPredicate<String,String> blockTagMatcher=(blockId,tagId)->false;

    private static final class Logical {
        final long id,time; final Incident owner;
        SoundObservation primary;
        final ArrayList<SoundObservation> evidence = new ArrayList<>();
        final EnumSet<SoundObservation.Route> routes = EnumSet.noneOf(SoundObservation.Route.class);
        Logical(long id,long time,Incident owner,SoundObservation event) { this.id=id; this.time=time; this.owner=owner; add(event); }
        void add(SoundObservation event) {
            routes.add(event.route()); if (evidence.size()<8) evidence.add(event);
            if (primary==null || actorRank(event.actor())>actorRank(primary.actor())
                    || (primary.actor()==event.actor() && event.baseScore()>primary.baseScore())
                    || (primary.blockId().isEmpty() && !event.blockId().isEmpty() && primary.actor()==event.actor())) primary=event;
        }
    }
    private static final class Incident {
        final long id; final String dimension; final ArrayDeque<Logical> events=new ArrayDeque<>();
        long first,last,lastNotified,queuedAt; Priority priority=Priority.INFO, notifiedPriority=Priority.INFO;
        boolean notified,queued,acknowledged,muted;
        SoundObservation primary;
        int count,score; String reason="Observed activity";
        Outputs outputs=Outputs.NONE;
        double cooldown;
        List<SoundObservation> lastEvidence=List.of();
        Incident(long id,String dimension,long now,SoundObservation event) { this.id=id;this.dimension=dimension; first=last=now; primary=event; }
    }

    public SoundEngine(SoundDetectorSettings settings,LongSupplier clock) { this.settings=Objects.requireNonNull(settings);this.clock=Objects.requireNonNull(clock);started=clock.getAsLong(); }
    public SoundEngine(SoundDetectorSettings settings) { this(settings,System::nanoTime); }
    /** Resolver arguments are the known block registry ID and tag ID without the leading '#'. */
    public void setBlockTagMatcher(BiPredicate<String,String> resolver) { blockTagMatcher=resolver==null?(blockId,tagId)->false:resolver; }

    public void accept(SoundObservation event,double bodyX,double bodyY,double bodyZ,String dimension) {
        if (!settings.enabled || event==null) return;
        long now=clock.getAsLong(); prune(now);
        if(event.route()==SoundObservation.Route.STOP_SOUND) {stopSustained(event);return;}
        this.bodyX=bodyX;this.bodyY=bodyY;this.bodyZ=bodyZ;
        dimension=dimension==null?"":dimension;
        if (!settings.categoryEnabled(event.category())) return;
        if (event.route()==SoundObservation.Route.CLIENT_DERIVED && !settings.includeClientDerived) return;
        if ((settings.suppressSelf && event.actor()==Actor.SELF)
                ||(settings.suppressProbableSelf && event.probableSelf() && event.actor()!=Actor.PLAYER)
                ||(settings.ignoreNonPlayers && event.actor()==Actor.NON_PLAYER)) { suppressMatching(event,dimension,now);return; }
        if (event.actor()==Actor.PLAYER && settings.trustedUuids.contains(event.actorUuid())) {suppressMatching(event,dimension,now);return;}
        if ((settings.playersOnly || settings.preset.equals("Players only")) && (event.actor()!=Actor.PLAYER || event.baseScore()==0)) return;
        double distance=distance(event,bodyX,bodyY,bodyZ,settings.horizontalOnly),height=event.y()-bodyY;
        if (event.located() && (distance<settings.minRange || distance>settings.range || height<settings.minHeight || height>settings.maxHeight)) return;
        boolean zoneMuted=false;
        for (var zone:settings.zones) if (event.located() && zone.contains(event.x(),event.y(),event.z(),dimension)) { if (zone.exclude) return; zoneMuted |= zone.mute; }

        Logical duplicate=findDuplicate(event,dimension,now);
        Incident incident;
        if (duplicate!=null) {
            duplicate.add(event); incident=duplicate.owner;
        } else {
            incident=findIncident(event,dimension,now);
            if (incident==null) {
                incident=new Incident(nextId++,dimension,now,event); active.put(incident.id,incident);
                while(active.size()>settings.maxIncidents) { Long id=active.keySet().iterator().next(); removeActive(id); dropped++; }
            }
            if (now-incident.last>nanos(Math.max(settings.evidenceSeconds,maxRuleWindow()))) {
                clearEvents(incident); incident.first=now; incident.notified=false; incident.acknowledged=false; incident.notifiedPriority=Priority.INFO;incident.priority=Priority.INFO;
            }
            Logical logical=new Logical(nextLogical++,now,incident,event); logicals.put(logical.id,logical); incident.events.addLast(logical);
            while(logicals.size()>settings.maxObservations) {
                var first=logicals.entrySet().iterator().next(); first.getValue().owner.events.remove(first.getValue()); logicals.remove(first.getKey()); dropped++;
            }
        }
        incident.last=now; incident.muted=zoneMuted;
        recompute(incident,bodyX,bodyY,bodyZ,now);
        remember(incident);
        if (!duplicateOnlyWeakens(duplicate,event)) qualify(incident,now);
        deliver(now);
    }

    private boolean duplicateOnlyWeakens(Logical duplicate,SoundObservation event) { return duplicate!=null && duplicate.primary!=event && duplicate.owner.notified; }

    private Logical findDuplicate(SoundObservation event,String dimension,long now) {
        if (settings.dedupeMillis<=0) return null;
        Logical match=null;
        for (Logical prior:logicals.values()) {
            if(now-prior.time>settings.dedupeMillis*1_000_000L || !prior.owner.dimension.equals(dimension) || prior.routes.contains(event.route())) continue;
            SoundObservation p=prior.primary;
            if (!p.actionKey().equals(event.actionKey()) || !p.category().equals(event.category())) continue;
            if (!p.actorUuid().isEmpty() && !event.actorUuid().isEmpty() && !p.actorUuid().equals(event.actorUuid())) continue;
            if (p.located() && event.located()) {
                double dx=p.x()-event.x(),dy=p.y()-event.y(),dz=p.z()-event.z();
                if(dx*dx+dy*dy+dz*dz>.81) continue;
                if(!p.blockId().isEmpty()&&!event.blockId().isEmpty()&&!p.blockId().equals(event.blockId())) continue;
            } else if(p.entityId()<0 || p.entityId()!=event.entityId()) continue;
            match=prior; // Match the latest compatible logical action, never a same-route repeat.
        }
        return match;
    }

    private void suppressMatching(SoundObservation event,String dimension,long now) {
        Logical duplicate=findDuplicate(event,dimension,now);
        if(duplicate==null) return;
        Incident incident=duplicate.owner;
        incident.events.remove(duplicate);logicals.remove(duplicate.id);
        if(incident.events.isEmpty()) { removeActive(incident.id);history.remove(incident.id);pending.remove(incident.id);notifications.removeIf(e->e.id()==incident.id); }
        else {recompute(incident,bodyX,bodyY,bodyZ,now);remember(incident);}
    }

    private void stopSustained(SoundObservation stop) {
        for(Incident incident:List.copyOf(active.values())) {
            if(!Set.of("music","transport","natural").contains(incident.primary.category())) continue;
            boolean matches=incident.events.stream().flatMap(logical->logical.evidence.stream()).anyMatch(event->
                    (stop.soundId().isEmpty()||stop.soundId().equals(event.soundId()))
                    &&(stop.soundSource().isEmpty()||stop.soundSource().equals(event.soundSource())));
            if(matches) {removeActive(incident.id);pending.remove(incident.id);notifications.removeIf(event->event.id()==incident.id);}
        }
    }

    private Incident findIncident(SoundObservation event,String dimension,long now) {
        Incident found=null;
        for (Incident candidate:active.values()) {
            if(!candidate.dimension.equals(dimension) || now-candidate.last>nanos(settings.markerSeconds)) continue;
            SoundObservation p=candidate.primary;
            if(event.actor()==Actor.PLAYER || p.actor()==Actor.PLAYER) {
                if(event.actor()==Actor.PLAYER && p.actor()==Actor.PLAYER && event.actorUuid().equals(p.actorUuid()) && event.entityId()==p.entityId()) found=candidate;
                continue;
            }
            if(p.actor()!=event.actor() || !p.category().equals(event.category())) continue;
            if(p.located()&&event.located()) {
                if(cell(p.x(),settings.groupingHorizontal)==cell(event.x(),settings.groupingHorizontal)
                        && cell(p.z(),settings.groupingHorizontal)==cell(event.z(),settings.groupingHorizontal)
                        && Math.abs(p.y()-event.y())<=settings.groupingVertical) found=candidate;
            } else if(p.entityId()>=0 && p.entityId()==event.entityId()) found=candidate;
        }
        return found;
    }

    private void recompute(Incident incident,double bodyX,double bodyY,double bodyZ,long now) {
        pruneEvents(incident,now);
        if(incident.events.isEmpty()) { incident.count=0;incident.score=0;incident.outputs=Outputs.NONE;return; }
        Set<String> families=new HashSet<>(); int basis=0,count=0,supportedCount=0,bestWeight=-1; boolean machineOnly=true,remote=false,probable=false;
        SoundObservation best=incident.events.getLast().primary;
        for(Logical logical:incident.events) {
            if(now-logical.time>nanos(settings.evidenceSeconds)) continue;
            count++;
            SoundObservation o=logical.primary;
            int weight=o.baseScore();
            if(o.global()) weight=0;
            if(o.actor()==Actor.PLAYER && weight>0) { weight=Math.max(weight,70); remote=true; }
            if(o.probableSelf()&&o.actor()!=Actor.PLAYER) {weight=Math.max(0,weight-25);probable=true;}
            if(weight>0) {supportedCount++;families.add(o.actionKey());}
            basis=Math.max(basis,weight);
            if(weight>0) machineOnly &= o.category().equals("machinery")||o.category().equals("music");
            if(weight>=bestWeight) {best=o;bestWeight=weight;}
        }
        incident.primary=best; incident.count=count;
        machineOnly &= supportedCount>0;
        int score=basis==0?0:Math.min(100,basis+Math.min(20,(families.size()-1)*10)+Math.min(10,(supportedCount-1)*2));
        if(machineOnly && !remote) score=settings.preset.equals("Base activity") && basis>0 ? Math.max(50,score) : Math.min(35,score);
        incident.score=score;
        Priority priority=score>=settings.warningThreshold?Priority.WARNING:Priority.INFO;
        if(score>=settings.criticalThreshold && (remote||families.size()>1)) priority=Priority.CRITICAL;
        boolean diagnostics=settings.preset.equals("Diagnostics");
        Outputs outputs=!diagnostics&&priority!=Priority.INFO?new Outputs(settings.popup,settings.chime,settings.chat,settings.worldMarkers):Outputs.NONE;
        double cooldown=outputs.any()?settings.cooldownSeconds:Double.POSITIVE_INFINITY;
        String reason=remote?"Event identifies a remote player":probable?"Possible local action; reduced score":machineOnly?"Operating machinery; actor unknown":best.baseScore()==0?"Unclassified/environmental observation":best.actor()==Actor.NON_PLAYER?"Non-player source":"Observed interaction; actor unknown";
        if(best.route()==SoundObservation.Route.CLIENT_DERIVED) reason+="; client-derived";
        boolean excluded=false;
        for(var rule:settings.rules) {
            if(!rule.enabled || !matches(rule,incident,bodyX,bodyY,bodyZ,now)) continue;
            if(rule.exclude) { excluded=true; reason="Excluded by "+rule.name;continue; }
            Priority rulePriority=priority(rule.priority);
            if(rulePriority.ordinal()>priority.ordinal()) priority=rulePriority;
            outputs=outputs.merge(new Outputs(rule.popup,rule.chime,rule.chat,rule.worldMarker));
            cooldown=Math.min(cooldown,rule.cooldownSeconds);
            reason+=(reason.contains("Rule: ")?", ":"; Rule: ")+rule.name;
        }
        boolean zoneMuted=false;
        for(var zone:settings.zones) if(best.located()&&zone.contains(best.x(),best.y(),best.z(),incident.dimension)) zoneMuted|=zone.exclude||zone.mute;
        incident.muted=excluded||zoneMuted||now<mutedUntil||sourceMutes.getOrDefault(incident.id,0L)>now;
        if(incident.muted) {
            outputs=Outputs.NONE;
        }
        incident.priority=priority.ordinal()>incident.priority.ordinal()?priority:incident.priority;
        incident.outputs=outputs; incident.reason=reason;
        incident.cooldown=Double.isFinite(cooldown)?cooldown:settings.cooldownSeconds;
    }

    private boolean matches(SoundDetectorSettings.Rule rule,Incident incident,double bx,double by,double bz,long now) {
        int count=0;Set<String> families=new HashSet<>();List<SoundObservation> matching=new ArrayList<>();
        for(Logical logical:incident.events) {
            if(now-logical.time>nanos(rule.windowSeconds)) continue;
            boolean logicalMatch=false;
            for(SoundObservation observed:logical.evidence) if(matchesObservation(rule,observed,incident.dimension,bx,by,bz)) {matching.add(observed);logicalMatch=true;}
            if(logicalMatch) {count++;families.add(logical.primary.actionKey());}
        }
        if(count<rule.minCount||families.size()<rule.minFamilies||incident.score<rule.minScore) return false;
        if(rule.novelOnly&&incident.notified) return false;
        if(rule.match==null) return true;
        for(SoundObservation observed:matching) if(matchesGroup(rule.match,incident,observed,distance(observed,bx,by,bz,settings.horizontalOnly),observed.y()-by,count,families.size(),0)) return true;
        return false;
    }

    private boolean matchesObservation(SoundDetectorSettings.Rule rule,SoundObservation o,String dimension,double bx,double by,double bz) {
        if(!rule.category.isEmpty()&&!rule.category.equals(o.category())) return false;
        if(!rule.soundPattern.isEmpty()&&!glob(rule.soundPattern,o.soundId())) return false;
        if(!rule.blockPattern.isEmpty()) {
            if(o.blockId().isEmpty()) return false;
            boolean matches=rule.blockPattern.startsWith("#")
                    ? blockTagMatcher.test(o.blockId(),rule.blockPattern.substring(1))
                    : glob(rule.blockPattern,o.blockId());
            if(!matches) return false;
        }
        if(!rule.actor.isEmpty()&&!rule.actor.equals(o.actor().name())) return false;
        if(!rule.route.isEmpty()&&!rule.route.equals(o.route().name())) return false;
        if(!rule.dimension.isEmpty()&&!rule.dimension.equals(dimension)) return false;
        if(!rule.playerUuid.isEmpty()&&!rule.playerUuid.equals(o.actorUuid())) return false;
        double d=distance(o,bx,by,bz,settings.horizontalOnly),h=o.y()-by;
        if(o.located()&&(d<rule.minDistance||d>rule.maxDistance||h<rule.minHeight||h>rule.maxHeight)) return false;
        if(!o.located()&&(rule.minDistance>0||rule.maxDistance<512||rule.minHeight>-4096||rule.maxHeight<4096)) return false;
        return true;
    }

    private boolean matchesGroup(SoundDetectorSettings.ConditionGroup group,Incident incident,SoundObservation o,double distance,double height,int count,int families,int depth) {
        if(depth>3) return false;
        List<Boolean> results=new ArrayList<>();
        for(var condition:group.conditions) {
            String value=switch(condition.field) {
                case "sound" -> o.soundId();case "block" -> o.blockId();case "category" -> o.category();case "actor" -> o.actor().name();
                case "route" -> o.route().name();case "dimension" -> incident.dimension;case "uuid" -> o.actorUuid();
                case "distance" -> Double.toString(distance);case "height" -> Double.toString(height);case "count" -> Integer.toString(count);
                case "families" -> Integer.toString(families);case "score" -> Integer.toString(incident.score); default -> null;
            };
            boolean match=false;
            if(value!=null) {
                match=switch(condition.op) {
                    case "EQUALS" -> value.equals(condition.value); case "NOT_EQUALS" -> !value.equals(condition.value);
                    case "MATCHES" -> glob(condition.value,value);case "AT_LEAST" -> numeric(value,condition.value,true);
                    case "AT_MOST" -> numeric(value,condition.value,false);default -> false;
                };
            }
            results.add(match);
        }
        for(var child:group.groups) results.add(matchesGroup(child,incident,o,distance,height,count,families,depth+1));
        return group.operation.equals("ANY")?results.stream().anyMatch(Boolean::booleanValue):results.stream().allMatch(Boolean::booleanValue);
    }
    private static boolean numeric(String left,String right,boolean atLeast) {try {double a=Double.parseDouble(left),b=Double.parseDouble(right);return Double.isFinite(a)&&Double.isFinite(b)&&(atLeast?a>=b:a<=b);}catch(NumberFormatException e){return false;}}

    private void qualify(Incident incident,long now) {
        if(!incident.outputs.any()||incident.acknowledged||incident.count==0) return;
        boolean escalation=incident.notified && incident.priority.ordinal()>incident.notifiedPriority.ordinal();
        if(incident.notified&&!escalation) return;
        if(incident.notified&&now-incident.lastNotified<nanos(incident.cooldown)) return;
        boolean directlyAttributed=incident.primary.actor()==Actor.PLAYER&&incident.primary.baseScore()>0;
        if(now-started<nanos(settings.warmupSeconds)&&!directlyAttributed) return;
        if(incident.queued) return;
        if(pending.size()>=32) {dropped++;return;}
        pending.addLast(incident.id);incident.queued=true;incident.queuedAt=now;
    }
    private void deliver(long now) {
        while(!delivered.isEmpty()&&now-delivered.peekFirst()>=5_000_000_000L) delivered.removeFirst();
        int attempts=pending.size();
        while(!pending.isEmpty()&&delivered.size()<3&&attempts-->0) {
            Incident incident=active.get(pending.removeFirst());
            if(incident==null) continue;
            boolean immediate=incident.primary.actor()==Actor.PLAYER&&incident.primary.baseScore()>0;
            if(!immediate&&now-incident.queuedAt<settings.dedupeMillis*1_000_000L) {pending.addLast(incident.id);continue;}
            incident.queued=false;
            if(incident.acknowledged||incident.muted||!incident.outputs.any()||now<mutedUntil||sourceMutes.getOrDefault(incident.id,0L)>now||now-incident.last>nanos(settings.durationSeconds)) continue;
            if(notifications.size()>=32) {notifications.removeFirst();dropped++;}
            notifications.addLast(snapshot(incident));delivered.addLast(now);incident.notified=true;incident.lastNotified=now;incident.notifiedPriority=incident.priority;
        }
    }

    public void tick() {long now=clock.getAsLong();prune(now);for(Incident incident:active.values()) recompute(incident,bodyX,bodyY,bodyZ,now);deliver(now);}
    private void prune(long now) {
        for(var entry:List.copyOf(sourceMutes.entrySet())) if(entry.getValue()<=now) {sourceMutes.remove(entry.getKey());Incident incident=active.get(entry.getKey());if(incident!=null){incident.muted=false;incident.notified=false;}}
        for(Incident incident:List.copyOf(active.values())) {
            pruneEvents(incident,now);
            if(now-incident.last>nanos(settings.markerSeconds)) removeActive(incident.id);
        }
    }
    private void pruneEvents(Incident incident,long now) {
        while(!incident.events.isEmpty()&&now-incident.events.peekFirst().time>nanos(Math.max(settings.evidenceSeconds,maxRuleWindow()))) {
            Logical logical=incident.events.removeFirst();logicals.remove(logical.id);
        }
    }
    private double maxRuleWindow() {double max=settings.evidenceSeconds;for(var rule:settings.rules) if(rule.enabled) max=Math.max(max,rule.windowSeconds);return max;}
    private void clearEvents(Incident incident) {for(Logical logical:incident.events) logicals.remove(logical.id);incident.events.clear();}
    private void removeActive(long id) {Incident incident=active.remove(id);if(incident!=null) clearEvents(incident);sourceMutes.remove(id);}
    private void remember(Incident incident) {history.remove(incident.id);history.put(incident.id,snapshot(incident));while(history.size()>settings.historyLimit) history.remove(history.keySet().iterator().next());}
    private SoundIncident snapshot(Incident incident) {
        var o=incident.primary; List<SoundObservation> evidence=new ArrayList<>();
        var descending=incident.events.descendingIterator();
        while(descending.hasNext()&&evidence.size()<16) for(var event:descending.next().evidence) {if(evidence.size()==16) break;evidence.add(event);}
        if(evidence.isEmpty()) evidence.addAll(incident.lastEvidence);else incident.lastEvidence=List.copyOf(evidence);
        return new SoundIncident(incident.id,o.actionKey(),o.label(),o.category(),o.blockId(),o.soundId(),o.global()?Double.NaN:o.x(),o.global()?Double.NaN:o.y(),o.global()?Double.NaN:o.z(),o.actorUuid(),o.actorName(),o.actor(),o.probableSelf(),incident.count,incident.score,incident.priority,incident.reason,incident.first,incident.last,incident.muted,List.copyOf(evidence));
    }
    public List<SoundIncident> incidents() {return active.values().stream().map(this::snapshot).sorted(Comparator.comparingLong(SoundIncident::lastNanos).reversed()).toList();}
    public List<SoundIncident> history() {List<SoundIncident> result=new ArrayList<>(history.values());Collections.reverse(result);return List.copyOf(result);}
    public List<SoundIncident> drainNotifications() {List<SoundIncident> result=List.copyOf(notifications);notifications.clear();return result;}
    public Outputs outputs(long incidentId) {Incident incident=active.get(incidentId);return incident==null?Outputs.NONE:incident.outputs;}
    public void acknowledge(long id) {Incident incident=active.get(id);if(incident!=null) {incident.acknowledged=true;incident.queued=false;}pending.remove(id);notifications.removeIf(e->e.id()==id);}
    public void muteSource(long id,double seconds) {sourceMutes.put(id,clock.getAsLong()+nanos(SoundDetectorSettings.finite(seconds,0,3600,60)));Incident incident=active.get(id);if(incident!=null) {incident.muted=true;incident.outputs=Outputs.NONE;incident.queued=false;remember(incident);}pending.remove(id);notifications.removeIf(e->e.id()==id);}
    public void mute(double seconds) {mutedUntil=clock.getAsLong()+nanos(SoundDetectorSettings.finite(seconds,0,3600,60));pending.clear();notifications.clear();for(Incident incident:active.values()) {incident.queued=false;incident.outputs=Outputs.NONE;}}
    public void clearHistory() {history.clear();}
    public void reset() {active.clear();history.clear();logicals.clear();pending.clear();notifications.clear();delivered.clear();sourceMutes.clear();mutedUntil=0;dropped=0;started=clock.getAsLong();}
    public long droppedObservations() {return dropped;}
    public boolean muted() {return clock.getAsLong()<mutedUntil;}
    private static long cell(double value,double width) {return (long)Math.floor(value/width);}
    private static int actorRank(Actor actor) {return switch(actor) {case SELF,PLAYER -> 3;case NON_PLAYER -> 2;case UNKNOWN -> 0;};}
    private static long nanos(double seconds) {return (long)(seconds*1_000_000_000L);}
    private static Priority priority(String value) {try{return Priority.valueOf(value);}catch(IllegalArgumentException|NullPointerException e){return Priority.WARNING;}}
    private static double distance(SoundObservation o,double x,double y,double z,boolean horizontal) {if(!o.located()) return Double.NaN;double dx=o.x()-x,dy=horizontal?0:o.y()-y,dz=o.z()-z;return Math.sqrt(dx*dx+dy*dy+dz*dz);}
    /** Bounded simple wildcard matching, with no regex interpretation or executable expressions. */
    public static boolean glob(String pattern,String value) {
        if(pattern==null||value==null||pattern.length()>128||value.length()>256) return false;
        int p=0,v=0,star=-1,retry=0;
        while(v<value.length()) {if(p<pattern.length()&&pattern.charAt(p)==value.charAt(v)) {p++;v++;}else if(p<pattern.length()&&pattern.charAt(p)=='*'){star=p++;retry=v;}else if(star>=0){p=star+1;v=++retry;}else return false;}
        while(p<pattern.length()&&pattern.charAt(p)=='*')p++;
        return p==pattern.length();
    }
}
