package dev.donut.client.gui;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.SoundDetectorSettings;
import dev.donut.client.sound.SoundIncident;
import dev.donut.client.sound.SoundObservation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Presentation only: no world scans, audio callbacks, or rule evaluation during rendering. */
public final class SoundAlertHud {
    private SoundAlertHud() { }

    public record Bounds(int x, int y, int width, int height) {
        public boolean contains(double px, double py) { return px >= x && py >= y && px < x + width && py < y + height; }
    }

    public static Bounds layout(int screenW, int screenH, ClientConfig config) {
        var s = config.soundDetector;
        double scale = Math.min(s.scale, Math.max(.25, (screenW - 12.0) / Math.max(160, s.width)));
        int width = Math.min(screenW - 12, (int)Math.round(s.width * scale));
        scale=width/(double)s.width;
        int lines=1+(s.showActor?1:0)+(s.showDistance||s.showDirection||s.showHeight?1:0)
                +(s.showCoordinates&&!s.hideCoordinates?1:0)+(s.showReason?1:0)+(s.showAge?1:0);
        if(s.compact||s.material.equals("Minimal"))lines=Math.min(2,lines);
        int height = Math.min(screenH - 12, (int)Math.ceil((s.padding*2+(15+lines*12)*s.fontScale) * scale));
        return new Bounds(6 + (int)Math.round(Math.max(0, screenW - width - 12) * s.x),
                6 + (int)Math.round(Math.max(0, screenH - height - 12) * s.y), width, height);
    }

    public static void moveTo(ClientConfig config, int w, int h, double x, double y) {
        Bounds b = layout(w, h, config);
        config.soundDetector.x = Math.clamp((x - 6) / Math.max(1, w - b.width - 12.0), 0, 1);
        config.soundDetector.y = Math.clamp((y - 6) / Math.max(1, h - b.height - 12.0), 0, 1);
    }

    public static void render(GuiGraphics g, ClientConfig config, List<SoundIncident> incidents) {
        if (incidents.isEmpty()) return;
        var mc = Minecraft.getInstance();
        var s = config.soundDetector;
        List<SoundIncident> ordered = new ArrayList<>(incidents);
        if (s.priorityFirst) ordered.sort(Comparator.comparingInt((SoundIncident i) -> i.priority().ordinal()).reversed()
                .thenComparing(Comparator.comparingLong(SoundIncident::lastNanos).reversed()));
        Bounds b = layout(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(), config);
        double scale = b.width() / (double)s.width;
        int gap = Math.max(3, (int)Math.round(6 * scale));
        int fit = Math.max(1,(mc.getWindow().getGuiScaledHeight()-12+gap)/(b.height()+gap));
        int shown = Math.min(Math.min(s.maxCards,fit), ordered.size());
        int total = shown * b.height() + Math.max(0, shown - 1) * gap;
        int top = Math.max(6, Math.min(b.y(), mc.getWindow().getGuiScaledHeight() - total - 6));
        for (int n = 0; n < shown; n++) {
            SoundIncident incident = ordered.get(n);
            List<String> lines = lines(incident, config);
            var controller=dev.donut.client.sound.SoundDetectorController.get();
            draw(g, mc.font, config, b.x(), top + n * (b.height() + gap), b.width(), b.height(),
                    incident.priority().name(), lines, incident.priority() == SoundIncident.Priority.CRITICAL,
                    s.showCount && incident.count() > 1 ? "x" + incident.count() : "",
                    controller==null?incident.firstNanos():controller.shownAt(incident.id()),controller==null?Long.MAX_VALUE:controller.expiresAt(incident.id()));
        }
        if (ordered.size() > shown) UiPaint.smallText(g, mc.font, "+" + (ordered.size() - shown) + " more in history",
                b.x() + 8, Math.min(mc.getWindow().getGuiScaledHeight() - 12, top + total + 2), 0xFFC5CCD9);
    }

    public static void preview(GuiGraphics g, ClientConfig config, boolean critical) {
        var mc = Minecraft.getInstance();
        Bounds b = layout(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(), config);
        long time=System.nanoTime()-400_000_000L;
        double px=mc.player==null?0:mc.player.getX(),py=mc.player==null?72:mc.player.getY(),pz=mc.player==null?0:mc.player.getZ();
        var actor=critical?SoundObservation.Actor.PLAYER:SoundObservation.Actor.UNKNOWN;
        var observation=new SoundObservation(time,SoundObservation.Route.BLOCK_EVENT,"minecraft:block.chest.open","containers","container.open",
                critical?"Block breaking - stone material":"Chest opened","minecraft:chest",px+16,py-8,pz-16,-1,
                critical?"00000000-0000-0000-0000-000000000001":"",critical?"Alex":"",actor,.5f,1,0,1,1,false,critical?85:50);
        var incident=new SoundIncident(-1,observation.actionKey(),observation.label(),observation.category(),observation.blockId(),observation.soundId(),
                observation.x(),observation.y(),observation.z(),observation.actorUuid(),observation.actorName(),actor,false,3,critical?85:50,
                critical?SoundIncident.Priority.CRITICAL:SoundIncident.Priority.WARNING,"Preview only - no detection is recorded",time,time,false,List.of(observation));
        draw(g, mc.font, config, b.x(), b.y(), b.width(), b.height(), critical ? "CRITICAL" : "WARNING", lines(incident,config),
                critical, config.soundDetector.showCount ? "x3" : "", 0,Long.MAX_VALUE);
    }

    public static List<String> lines(SoundIncident i, ClientConfig config) {
        var s = config.soundDetector;
        var mc = Minecraft.getInstance();
        List<String> result = new ArrayList<>();
        String actor=hideNames(config)?actorLabel(i):actorLabel(i)+(i.actorName()==null||i.actorName().isBlank()?"":" - "+clean(i.actorName()));
        result.add(template(s.titleTemplate,i,actor,config));
        if (s.showActor) result.add(template(s.detailTemplate,i,actor,config));
        if (i.located() && mc.player != null) {
            double dx = i.x() - mc.player.getX(), dz = i.z() - mc.player.getZ(), dy = i.y() - mc.player.getY();
            List<String> location = new ArrayList<>();
            if (s.showDistance) location.add(String.format(Locale.ROOT,"%.0f m",Math.sqrt(dx*dx+dy*dy+dz*dz)));
            if (s.showDirection) location.add(direction(dx, dz));
            if (s.showHeight) location.add(Math.abs(dy) < 2 ? "same height" : String.format(Locale.ROOT,"%.0f m %s",Math.abs(dy),dy < 0 ? "below" : "above"));
            if (!location.isEmpty()) result.add(String.join(" - ", location));
            if (s.showCoordinates && !s.hideCoordinates) result.add(String.format(Locale.ROOT,"XYZ %.1f / %.1f / %.1f",i.x(),i.y(),i.z()));
        }
        if (s.showReason) result.add(clean(i.reason()));
        if (s.showAge) result.add(String.format(Locale.ROOT,"Last seen %.1f s ago",Math.max(0,System.nanoTime()-i.lastNanos())/1e9));
        return result;
    }

    private static String actorLabel(SoundIncident i) {
        return i.probableSelf() ? "Probable local action" : switch(i.actor()){
            case PLAYER->"Identified player";case SELF->"Local action";case NON_PLAYER->"Non-player entity";case UNKNOWN->"Actor unknown";};
    }
    private static String template(String pattern,SoundIncident i,String actor,ClientConfig config){
        var s=config.soundDetector;var mc=Minecraft.getInstance();String distance="unknown",height="unknown",direction="unknown";
        if(i.located()&&mc.player!=null){double dx=i.x()-mc.player.getX(),dz=i.z()-mc.player.getZ(),dy=i.y()-mc.player.getY();distance=String.format(Locale.ROOT,"%.0f m",Math.sqrt(dx*dx+dy*dy+dz*dz));height=Math.abs(dy)<2?"same height":String.format(Locale.ROOT,"%.0f m %s",Math.abs(dy),dy<0?"below":"above");direction=direction(dx,dz);}
        return clean(pattern.replace("{event}",clean(i.label())).replace("{actor}",s.showActor?actor:"")
                .replace("{distance}",s.showDistance?distance:"").replace("{direction}",s.showDirection?direction:"")
                .replace("{height}",s.showHeight?height:"").replace("{count}",s.showCount?String.valueOf(i.count()):"")
                .replace("{reason}",s.showReason?clean(i.reason()):""));
    }
    private static boolean hideNames(ClientConfig c) { return c.soundDetector.hideNames || c.nameHider.enabled; }
    private static String direction(double dx, double dz) {
        if (Math.hypot(dx,dz) < .5) return "here";
        String[] names = {"E","SE","S","SW","W","NW","N","NE"};
        return names[Math.floorMod((int)Math.round(Math.atan2(dz,dx)*4/Math.PI),8)];
    }
    private static String clean(String text) { return text == null ? "" : text.replaceAll("[\\p{Cntrl}§]", ""); }

    private static void draw(GuiGraphics g, Font font, ClientConfig config, int x, int y, int w, int h,
                             String priority, List<String> content, boolean critical, String badge, long first,long expires) {
        SoundDetectorSettings s = config.soundDetector;
        float scale = w / (float)s.width;
        int color = critical ? s.criticalColor : s.warningColor;
        long now=System.nanoTime();
        double fade=1;
        if(first!=0&&s.animation&&config.clickGuiStyle.animationSpeed!=0){
            double in=s.fadeInMillis==0?1:Math.clamp((now-first)/(s.fadeInMillis*1_000_000.0),0,1);
            double out=s.fadeOutMillis==0||expires==Long.MAX_VALUE?1:Math.clamp((expires-now)/(s.fadeOutMillis*1_000_000.0),0,1);
            fade=Math.min(in,out);
        }
        double previous = UiPaint.pushOpacity(fade);
        g.pose().pushMatrix();
        try {
            g.pose().translate(x+(float)((1-fade)*s.slidePixels*scale), y);
            g.pose().scale(scale,scale);
            float localH = h/scale;
            int alpha = (int)Math.round(s.opacity * 255);
            if (s.shadow) UiPaint.shadow(g,0,2,s.width,(int)Math.ceil(localH),s.radius,UiPaint.withOpacity(0x60000000));
            if (s.material.equals("Glass") && config.clickGuiStyle.liquidGlass)
                GlassPanel.drawGradient(g,0,0,s.width,localH,s.radius,UiPaint.withOpacity((alpha<<24)|(s.topColor&0xFFFFFF)),UiPaint.withOpacity((alpha<<24)|(s.bottomColor&0xFFFFFF)),.9f);
            else UiPaint.round(g,0,0,s.width,localH,s.radius,(alpha<<24)|(s.topColor&0xFFFFFF));
            for(int border=0;border<s.borderThickness;border++)UiPaint.outline(g,border,border,s.width-border*2,localH-border*2,Math.max(0,s.radius-border),((int)(s.borderOpacity*255)<<24)|(s.borderColor&0xFFFFFF));
            g.fill(0,(int)s.radius,3,(int)(localH-s.radius),UiPaint.withOpacity(0xFF000000|(color&0xFFFFFF)));
            int pad = s.padding;
            float fs=(float)s.fontScale;
            scaledText(g,font,priority,pad,pad,0xFF000000|(color&0xFFFFFF),fs,true);
            if (!badge.isEmpty()) scaledText(g,font,badge,s.width-pad-UiPaint.width(font,badge)*fs,pad,0xFF000000|(s.textColor&0xFFFFFF),fs,false);
            float lineY = pad + 15*fs;
            int textColor = ((int)(s.textOpacity*255)<<24)|(s.textColor&0xFFFFFF);
            int lineLimit = s.compact || s.material.equals("Minimal") ? 2 : Math.max(1,(int)((localH-lineY-pad)/(12*fs)));
            for (int n = 0; n < content.size() && n < lineLimit; n++) {
                String line = font.plainSubstrByWidth(content.get(n),Math.max(20,(int)((s.width-pad*2)/fs)));
                scaledText(g,font,line,pad,lineY+n*12*fs,textColor,fs,false);
            }
        } finally { g.pose().popMatrix(); UiPaint.popOpacity(previous); }
    }
    private static void scaledText(GuiGraphics g,Font font,String text,float x,float y,int color,float scale,boolean bold){
        g.pose().pushMatrix();try{g.pose().translate(x,y);g.pose().scale(scale,scale);if(bold)UiPaint.strong(g,font,text,0,0,color);else UiPaint.text(g,font,text,0,0,color);}finally{g.pose().popMatrix();}
    }
}
