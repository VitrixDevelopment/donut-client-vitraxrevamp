package dev.donut.client.gui;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.SoundDetectorSettings;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Guided rules/expected-area list. Complex typed condition groups remain editable in Advanced JSON. */
public final class SoundRulesScreen extends Screen {
    private final Screen parent;
    private final ClientConfig config;
    private final Runnable save;
    private boolean areas;
    private int page;
    private String status="Rules combine their filters with AND. Empty filters match any source.";
    public SoundRulesScreen(Screen parent,ClientConfig config,Runnable save){super(Component.literal("Sound rules and expected areas"));this.parent=parent;this.config=config;this.save=save;}
    private int perPage(){return Math.max(1,(height-120)/28);}
    @Override protected void init(){
        addRenderableWidget(Button.builder(Component.literal(areas?"Show rules":"Show expected areas"),b->{areas=!areas;page=0;rebuildWidgets();}).bounds(14,34,150,20).build());
        addRenderableWidget(Button.builder(Component.literal(areas?"Add current area":"Add rule"),b->{
            if(areas){if(config.soundDetector.zones.size()>=64){status="Maximum 64 areas.";return;}var z=new SoundDetectorSettings.Zone();if(minecraft.player!=null){z.x=minecraft.player.getX();z.y=minecraft.player.getY();z.z=minecraft.player.getZ();}if(minecraft.level!=null)z.dimension=minecraft.level.dimension().identifier().toString();config.soundDetector.zones.add(z);save.run();minecraft.setScreen(new SoundRuleFormScreen(this,config,save,z));}
            else{if(config.soundDetector.rules.size()>=64){status="Maximum 64 rules.";return;}var r=new SoundDetectorSettings.Rule();config.soundDetector.rules.add(r);save.run();minecraft.setScreen(new SoundRuleFormScreen(this,config,save,r));}
        }).bounds(170,34,135,20).build());
        int count=areas?config.soundDetector.zones.size():config.soundDetector.rules.size();page=Math.clamp(page,0,Math.max(0,(count-1)/perPage()));
        for(int n=page*perPage();n<Math.min(count,(page+1)*perPage());n++){
            int index=n,y=64+(n-page*perPage())*28;
            String name=areas?config.soundDetector.zones.get(n).name:config.soundDetector.rules.get(n).name;
            addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(name,width-240)),b->{Object entry=areas?config.soundDetector.zones.get(index):config.soundDetector.rules.get(index);minecraft.setScreen(new SoundRuleFormScreen(this,config,save,entry));}).bounds(14,y,Math.max(100,width-210),22).build());
            addRenderableWidget(Button.builder(Component.literal(areas?(config.soundDetector.zones.get(n).mute?"Muted":"Observe"):(config.soundDetector.rules.get(n).enabled?"Enabled":"Disabled")),b->{if(areas){var z=config.soundDetector.zones.get(index);z.mute=!z.mute;}else{var r=config.soundDetector.rules.get(index);r.enabled=!r.enabled;}save.run();rebuildWidgets();}).bounds(width-190,y,90,22).build());
            addRenderableWidget(Button.builder(Component.literal("Delete"),b->{if(areas)config.soundDetector.zones.remove(index);else config.soundDetector.rules.remove(index);save.run();rebuildWidgets();}).bounds(width-96,y,82,22).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Previous"),b->{if(page>0){page--;rebuildWidgets();}}).bounds(14,height-30,90,20).build());
        addRenderableWidget(Button.builder(Component.literal("Next"),b->{if((page+1)*perPage()<count){page++;rebuildWidgets();}}).bounds(108,height-30,90,20).build());
        addRenderableWidget(Button.builder(Component.literal("Advanced JSON"),b->minecraft.setScreen(new SoundSettingsJsonScreen(this,config,save))).bounds(width-224,height-30,116,20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"),b->onClose()).bounds(width-104,height-30,90,20).build());
    }
    @Override public void render(GuiGraphics g,int mx,int my,float delta){g.fill(0,0,width,height,0xF0101722);g.drawCenteredString(font,areas?"Expected sound areas":"Custom sound rules",width/2,12,0xFFF1F5FB);g.drawString(font,font.plainSubstrByWidth(status,width-28),14,height-46,0xFFB3C8DB,false);super.render(g,mx,my,delta);}
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
