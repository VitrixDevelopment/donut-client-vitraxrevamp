package dev.donut.client.gui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.SoundDetectorSettings;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Explicit advanced import/export editor. Imported values are data, never executable expressions. */
public final class SoundSettingsJsonScreen extends Screen {
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private final Screen parent;
    private final ClientConfig config;
    private final Runnable save;
    private MultiLineEditBox editor;
    private String draft,status="Advanced: edit rules, source filters, profiles, areas, or safe text templates.";
    public SoundSettingsJsonScreen(Screen parent,ClientConfig config,Runnable save){super(Component.literal("Advanced sound preferences"));this.parent=parent;this.config=config;this.save=save;draft=JSON.toJson(config.soundDetector);}
    @Override protected void init(){
        editor=addRenderableWidget(MultiLineEditBox.builder().setX(14).setY(48).setShowBackground(true).setShowDecorations(true).build(font,width-28,Math.max(40,height-106),Component.literal("Sound preferences JSON")));
        editor.setCharacterLimit(60000);editor.setValue(draft);editor.setValueListener(v->draft=v);
        int bw=Math.min(120,(width-40)/4),left=(width-(bw+4)*4+4)/2;
        addRenderableWidget(Button.builder(Component.literal("Apply"),b->apply()).bounds(left,height-30,bw,20).build());
        addRenderableWidget(Button.builder(Component.literal("Copy"),b->{minecraft.keyboardHandler.setClipboard(draft);status="Copied preferences to clipboard.";}).bounds(left+bw+4,height-30,bw,20).build());
        addRenderableWidget(Button.builder(Component.literal("Paste"),b->{String clipboard=minecraft.keyboardHandler.getClipboard();if(clipboard.length()<=60000){editor.setValue(clipboard);status="Review, then choose Apply.";}else status="Import is too large (maximum 60,000 characters).";}).bounds(left+2*(bw+4),height-30,bw,20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"),b->onClose()).bounds(left+3*(bw+4),height-30,bw,20).build());
    }
    private void apply(){
        try{
            if(draft.length()>60000)throw new IllegalArgumentException("Import too large");
            // Bound nesting before Gson traverses attacker-controlled data.
            int depth=0,max=0;boolean quoted=false,escape=false;
            for(char c:draft.toCharArray()){
                if(quoted){if(escape)escape=false;else if(c=='\\')escape=true;else if(c=='"')quoted=false;continue;}
                if(c=='"')quoted=true;else if(c=='{'||c=='['){depth++;max=Math.max(depth,max);}else if(c=='}'||c==']')depth--;
                if(depth<0||max>16)throw new IllegalArgumentException("Maximum nesting is 16");
            }
            var parsed=JsonParser.parseString(draft);if(!parsed.isJsonObject())throw new IllegalArgumentException("Expected an object");
            SoundDetectorSettings result=JSON.fromJson(parsed,SoundDetectorSettings.class);if(result==null)throw new IllegalArgumentException("Empty settings");
            result.sanitize();config.soundDetector=result;save.run();status="Applied validated preferences.";
        }catch(RuntimeException e){status="Could not apply: "+e.getMessage();}
    }
    @Override public void render(GuiGraphics g,int mx,int my,float delta){g.fill(0,0,width,height,0xF0101722);g.drawCenteredString(font,"Advanced sound preferences",width/2,12,0xFFF1F5FB);g.drawString(font,font.plainSubstrByWidth(status,width-28),14,31,0xFFBCCFE3,false);super.render(g,mx,my,delta);}
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
