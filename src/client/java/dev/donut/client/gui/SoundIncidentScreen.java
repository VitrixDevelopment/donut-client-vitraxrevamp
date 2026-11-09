package dev.donut.client.gui;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.sound.SoundIncident;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Read-only provenance inspector; a raw event's source and inferred actor remain separate. */
public final class SoundIncidentScreen extends Screen {
    private final Screen parent;
    private final ClientConfig config;
    private final SoundIncident incident;
    private int page;
    public SoundIncidentScreen(Screen parent,ClientConfig config,SoundIncident incident){super(Component.literal("Sound evidence"));this.parent=parent;this.config=config;this.incident=incident;}
    private int perPage(){return Math.max(1,(height-82)/12);}
    private List<String> lines(){
        var out=new ArrayList<>(SoundAlertHud.lines(incident,config));
        out.add("Priority: "+incident.priority()+" / score: "+incident.score()+" heuristic points");
        out.add("Category: "+incident.category()+" / logical action: "+incident.actionKey());
        out.add("Known block: "+(incident.blockId().isEmpty()?"unknown or shared material":incident.blockId()));
        out.add("Sound: "+(incident.soundId().isEmpty()?"event-derived":incident.soundId()));
        out.add("Logical actions: "+incident.count()+" / retained observations: "+incident.evidence().size());
        for(int n=0;n<incident.evidence().size();n++){
            var e=incident.evidence().get(n);
            out.add("");out.add("Observation "+(n+1)+" - "+e.route()+" / "+e.actor());
            out.add("Action: "+e.actionKey()+" / "+e.label());
            out.add("Sound ID: "+(e.soundId().isEmpty()?"none":e.soundId()));
            out.add("Block ID: "+(e.blockId().isEmpty()?"unknown":e.blockId()));
            out.add("Event code: "+e.eventCode()+" / payload: "+e.data()+" / entity ID: "+e.entityId());
            out.add(String.format(Locale.ROOT,"Volume %.2f / pitch %.2f / variant seed %d",e.volume(),e.pitch(),e.seed()));
            if(e.global())out.add("Global effect - no localized source is claimed.");
            else if(e.located()&&!config.soundDetector.hideCoordinates)out.add(String.format(Locale.ROOT,"Received position: %.3f / %.3f / %.3f",e.x(),e.y(),e.z()));
            out.add("Probable self: "+e.probableSelf()+" / base evidence points: "+e.baseScore());
            if(!config.soundDetector.hideNames&&!config.nameHider.enabled&&!e.actorName().isEmpty())out.add("Identified actor: "+e.actorName()+" / "+e.actorUuid());
        }
        return out;
    }
    @Override protected void init(){
        addRenderableWidget(Button.builder(Component.literal("Previous"),b->{if(page>0)page--;}).bounds(14,height-30,90,20).build());
        addRenderableWidget(Button.builder(Component.literal("Next"),b->{if((page+1)*perPage()<lines().size())page++;}).bounds(108,height-30,90,20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"),b->onClose()).bounds(width-104,height-30,90,20).build());
    }
    @Override public void render(GuiGraphics g,int mx,int my,float delta){
        g.fill(0,0,width,height,0xF0101722);g.drawCenteredString(font,"Sound event evidence - page "+(page+1),width/2,12,0xFFF1F5FB);
        var lines=lines();page=Math.clamp(page,0,Math.max(0,(lines.size()-1)/perPage()));
        for(int n=page*perPage();n<Math.min(lines.size(),(page+1)*perPage());n++)g.drawString(font,font.plainSubstrByWidth(lines.get(n).replaceAll("[\\p{Cntrl}§]",""),width-28),14,38+(n-page*perPage())*12,0xFFCCD8E6,false);
        super.render(g,mx,my,delta);
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){page=Math.clamp(page-(int)Math.signum(vertical),0,Math.max(0,(lines().size()-1)/perPage()));return true;}
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
