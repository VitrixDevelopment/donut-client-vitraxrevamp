package dev.donut.client.gui;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.SoundDetectorSettings;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Native, keyboard-accessible editor for rule filters, output choices, and expected areas. */
public final class SoundRuleFormScreen extends Screen {
    private final Screen parent;
    private final ClientConfig config;
    private final Runnable save;
    private final Object entry;
    private int page;
    private final List<RowLabel> labels=new ArrayList<>();
    private String status="Changes save automatically. Sound/block filters accept a bounded * wildcard.";
    private record RowLabel(String text,int y) { }
    public SoundRuleFormScreen(Screen parent,ClientConfig config,Runnable save,Object entry){super(Component.literal("Edit sound rule"));this.parent=parent;this.config=config;this.save=save;this.entry=entry;}
    private List<Field> fields(){List<Field> out=new ArrayList<>();for(Field f:entry.getClass().getFields())if(!Modifier.isStatic(f.getModifiers())&&(f.getType().isPrimitive()||f.getType()==String.class))out.add(f);out.sort(Comparator.comparingInt((Field f)->f.getName().equals("name")?0:f.getName().equals("enabled")?1:2).thenComparing(Field::getName));return out;}
    private int perPage(){return Math.max(1,(height-100)/28);}
    @Override protected void init(){
        labels.clear();var fields=fields();page=Math.clamp(page,0,Math.max(0,(fields.size()-1)/perPage()));
        int controlW=Math.max(100,Math.min(260,width/2-20));
        for(int n=page*perPage();n<Math.min(fields.size(),(page+1)*perPage());n++){
            Field f=fields.get(n);int y=42+(n-page*perPage())*28;String name=SoundSettingsScreen.label(f.getName());labels.add(new RowLabel(name,y+6));
            try{
                if(f.getType()==boolean.class)addRenderableWidget(Button.builder(Component.literal(f.getBoolean(entry)?"On":"Off"),b->{try{f.setBoolean(entry,!f.getBoolean(entry));changed();b.setMessage(Component.literal(f.getBoolean(entry)?"On":"Off"));}catch(ReflectiveOperationException e){status="Could not update filter.";}}).bounds(width-controlW-14,y,controlW,20).build());
                else if(List.of("priority","category","actor","route").contains(f.getName())){
                    List<String> options=switch(f.getName()){
                        case "priority"->List.of("INFO","WARNING","CRITICAL");
                        case "category"->{List<String> all=new ArrayList<>();all.add("");all.addAll(SoundDetectorSettings.CATEGORIES);yield all;}
                        case "actor"->List.of("","PLAYER","UNKNOWN","SELF","NON_PLAYER");
                        default->List.of("","POSITIONAL","ENTITY_SOUND","BLOCK_EVENT","LEVEL_EVENT","ENTITY_EVENT","EXPLOSION","MINING","DAMAGE","CLIENT_DERIVED");};
                    addRenderableWidget(Button.builder(Component.literal(f.get(entry).toString().isEmpty()?"Any":f.get(entry).toString()),b->{try{String next=options.get((options.indexOf(f.get(entry).toString())+1)%options.size());f.set(entry,next);changed();b.setMessage(Component.literal(next.isEmpty()?"Any":next));}catch(ReflectiveOperationException e){status="Could not update filter.";}}).bounds(width-controlW-14,y,controlW,20).build());
                }else{
                    EditBox edit=addRenderableWidget(new EditBox(font,width-controlW-14,y,controlW,20,Component.literal(name)));edit.setMaxLength(128);edit.setValue(String.valueOf(f.get(entry)));edit.setResponder(v->{try{if(f.getType()==String.class)f.set(entry,v);else if(f.getType()==int.class)f.setInt(entry,Integer.parseInt(v));else{double value=Double.parseDouble(v);if(!Double.isFinite(value))throw new NumberFormatException();f.setDouble(entry,value);}changed();edit.setTextColor(0xFFE8F1FA);}catch(ReflectiveOperationException|NumberFormatException e){edit.setTextColor(0xFFFF8993);status="Enter a valid value.";}});
                }
            }catch(ReflectiveOperationException e){status="Could not display a filter.";}
        }
        addRenderableWidget(Button.builder(Component.literal("Previous"),b->{if(page>0){page--;rebuildWidgets();}}).bounds(14,height-30,90,20).build());
        addRenderableWidget(Button.builder(Component.literal("Next"),b->{if((page+1)*perPage()<fields.size()){page++;rebuildWidgets();}}).bounds(108,height-30,90,20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"),b->onClose()).bounds(width-104,height-30,90,20).build());
    }
    private void changed(){if(entry instanceof SoundDetectorSettings.Rule r)r.sanitize();if(entry instanceof SoundDetectorSettings.Zone z)z.sanitize();save.run();status="Saved. All populated filters must match.";}
    @Override public void render(GuiGraphics g,int mx,int my,float delta){g.fill(0,0,width,height,0xF0101722);g.drawCenteredString(font,entry instanceof SoundDetectorSettings.Zone?"Expected area":"Custom sound rule",width/2,14,0xFFF1F5FB);for(RowLabel l:labels)g.drawString(font,font.plainSubstrByWidth(l.text,width/2-30),18,l.y,0xFFCCD8E6,false);g.drawString(font,font.plainSubstrByWidth(status,width-28),14,height-46,0xFFB3C8DB,false);super.render(g,mx,my,delta);}
    @Override public void onClose(){changed();minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
