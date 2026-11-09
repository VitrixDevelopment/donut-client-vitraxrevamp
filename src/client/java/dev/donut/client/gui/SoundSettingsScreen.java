package dev.donut.client.gui;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.SoundDetectorSettings;
import dev.donut.client.sound.SoundDetectorController;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Searchable native controls for every scalar preference, with validation before persistence. */
public final class SoundSettingsScreen extends Screen {
    private final Screen parent;
    private final ClientConfig config;
    private final Runnable save;
    private String query="",tab="Appearance",status="Changes save automatically.";
    private int page;
    private final List<Label> labels=new ArrayList<>();
    private record Label(String text,int x,int y) { }
    private static final List<String> TABS=List.of("Detection","Alerts","Appearance","Audio","Privacy");

    public SoundSettingsScreen(Screen parent,ClientConfig config,Runnable save){super(Component.literal("Sound Detector settings"));this.parent=parent;this.config=config;this.save=save;}
    private String group(String name){
        if(List.of("hideNames","hideCoordinates","showActor","showCoordinates","showDirection","showDistance","showHeight","showCount","showReason","showAge").contains(name))return "Privacy";
        if(name.toLowerCase(Locale.ROOT).contains("sound")||List.of("chime","volume","pitch","audioWhenHidden","repeatCount","repeatIntervalSeconds").contains(name))return "Audio";
        if(List.of("x","y","scale","fontScale","width","padding","radius","opacity","topColor","bottomColor","textColor","warningColor","criticalColor","borderColor","borderOpacity","textOpacity","shadow","borderThickness","material","compact","animation","fadeInMillis","fadeOutMillis","slidePixels","priorityFirst","titleTemplate","detailTemplate").contains(name))return "Appearance";
        if(name.toLowerCase(Locale.ROOT).contains("history")||name.toLowerCase(Locale.ROOT).contains("card")||name.toLowerCase(Locale.ROOT).contains("cooldown")||List.of("popup","chat","showInMenus","showWhenHudHidden","respectHud","durationSeconds","worldMarkers","markerSeconds","pinCritical").contains(name))return "Alerts";
        return "Detection";
    }
    private List<Field> fields(){
        List<Field> fields=new ArrayList<>();
        for(Field field:SoundDetectorSettings.class.getFields()){
            if(Modifier.isStatic(field.getModifiers())||List.of("enabled","favorite","preset","settingsVersion","maxObservations").contains(field.getName()))continue;
            Class<?> type=field.getType();if(type!=boolean.class&&type!=int.class&&type!=double.class&&type!=long.class&&type!=float.class&&type!=String.class)continue;
            if(!query.isBlank()?!label(field.getName()).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)):!group(field.getName()).equals(tab))continue;
            fields.add(field);
        }
        fields.sort(Comparator.comparing(Field::getName));return fields;
    }
    private int perPage(){return Math.max(1,(height-142)/30);}
    @Override protected void init(){
        labels.clear();
        int tabWidth=Math.max(40,Math.min(100,(width-28)/TABS.size()));int tabX=(width-tabWidth*TABS.size())/2;
        for(int n=0;n<TABS.size();n++){String chosen=TABS.get(n);addRenderableWidget(Button.builder(Component.literal((tab.equals(chosen)?"> ":"")+chosen),b->{tab=chosen;page=0;query="";rebuildWidgets();}).bounds(tabX+n*tabWidth,30,tabWidth-2,20).build());}
        EditBox search=addRenderableWidget(new EditBox(font,14,56,width-28,20,Component.literal("Search settings")));
        search.setMaxLength(80);search.setValue(query);search.setHint(Component.literal("Search all sound preferences"));
        search.setResponder(v->{query=v;page=0;rebuildSettingRows();});
        buildRows();
        addRenderableWidget(Button.builder(Component.literal("Previous"),b->{if(page>0){page--;rebuildWidgets();}}).bounds(14,height-52,85,20).build());
        addRenderableWidget(Button.builder(Component.literal("Next"),b->{if((page+1)*perPage()<fields().size()){page++;rebuildWidgets();}}).bounds(103,height-52,85,20).build());
        addRenderableWidget(Button.builder(Component.literal("Rules / areas"),b->minecraft.setScreen(new SoundRulesScreen(this,config,save))).bounds(width-230,height-52,105,20).build());
        addRenderableWidget(Button.builder(Component.literal("Advanced JSON"),b->minecraft.setScreen(new SoundSettingsJsonScreen(this,config,save))).bounds(width-121,height-52,107,20).build());
        addRenderableWidget(Button.builder(Component.literal("Preview / move"),b->minecraft.setScreen(new SoundAlertEditorScreen(this,config,save,false))).bounds(14,height-27,105,20).build());
        addRenderableWidget(Button.builder(Component.literal("Reset "+tab.toLowerCase(Locale.ROOT)),b->resetGroup()).bounds(123,height-27,120,20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"),b->onClose()).bounds(width-99,height-27,85,20).build());
    }
    // A new screen instance preserves search focus/cursor while rebuilding the rows on the next tick.
    private boolean refresh;
    private void rebuildSettingRows(){refresh=true;}
    @Override public void tick(){if(refresh){refresh=false;rebuildWidgets();for(var child:children())if(child instanceof EditBox e&&e.getY()==56){setInitialFocus(e);e.moveCursorToEnd(false);break;}}}
    private void buildRows(){
        var fields=fields();page=Math.clamp(page,0,Math.max(0,(fields.size()-1)/perPage()));
        int valueWidth=Math.max(100,Math.min(240,width/2-20));
        for(int n=page*perPage();n<Math.min(fields.size(),(page+1)*perPage());n++){
            Field field=fields.get(n);int y=84+(n-page*perPage())*30;
            labels.add(new Label(label(field.getName()),18,y+6));
            try{
                if(field.getType()==boolean.class){addRenderableWidget(Button.builder(Component.literal(field.getBoolean(config.soundDetector)?"On":"Off"),b->{try{field.setBoolean(config.soundDetector,!field.getBoolean(config.soundDetector));changed();b.setMessage(Component.literal(field.getBoolean(config.soundDetector)?"On":"Off"));}catch(ReflectiveOperationException e){status="Unable to change preference.";}}).bounds(width-valueWidth-14,y,valueWidth,20).build());}
                else if(field.getName().equals("material")){addRenderableWidget(Button.builder(Component.literal(field.get(config.soundDetector).toString()),b->{try{var options=List.of("Glass","Solid","Minimal");String current=field.get(config.soundDetector).toString();field.set(config.soundDetector,options.get((options.indexOf(current)+1)%options.size()));changed();b.setMessage(Component.literal(field.get(config.soundDetector).toString()));}catch(ReflectiveOperationException e){status="Unable to change material.";}}).bounds(width-valueWidth-14,y,valueWidth,20).build());}
                else{
                    EditBox edit=addRenderableWidget(new EditBox(font,width-valueWidth-14,y,valueWidth,20,Component.literal(label(field.getName()))));
                    edit.setMaxLength(field.getType()==String.class?256:32);
                    String value=field.getName().endsWith("Color")?String.format(Locale.ROOT,"#%06X",field.getInt(config.soundDetector)&0xFFFFFF):String.valueOf(field.get(config.soundDetector));edit.setValue(value);
                    edit.setResponder(v->{try{
                        Class<?> type=field.getType();Object parsed;
                        if(type==String.class)parsed=v;
                        else if(type==int.class)parsed=field.getName().endsWith("Color")?Integer.parseInt(v.replace("#",""),16):Integer.parseInt(v);
                        else if(type==long.class)parsed=Long.parseLong(v);
                        else if(type==float.class){float f=Float.parseFloat(v);if(!Float.isFinite(f))throw new NumberFormatException();parsed=f;}
                        else{double d=Double.parseDouble(v);if(!Double.isFinite(d))throw new NumberFormatException();parsed=d;}
                        field.set(config.soundDetector,parsed);changed();edit.setTextColor(0xFFE8F1FA);
                    }catch(ReflectiveOperationException|NumberFormatException e){edit.setTextColor(0xFFFF8993);status="Enter a valid value. Colors use #RRGGBB.";}});
                }
            }catch(ReflectiveOperationException e){status="Some preferences could not be displayed.";}
        }
    }
    private void changed(){config.soundDetector.sanitize();save.run();status="Saved. Numeric limits are applied automatically.";}
    private void resetGroup(){
        SoundDetectorSettings defaults=new SoundDetectorSettings();
        for(Field field:SoundDetectorSettings.class.getFields())if(!Modifier.isStatic(field.getModifiers())&&group(field.getName()).equals(tab)&&!List.of("enabled","favorite","preset","rules","zones","profiles","categories").contains(field.getName()))try{field.set(config.soundDetector,field.get(defaults));}catch(ReflectiveOperationException ignored){}
        changed();rebuildWidgets();
    }
    static String label(String text){String spaced=text.replaceAll("([a-z])([A-Z])","$1 $2");return Character.toUpperCase(spaced.charAt(0))+spaced.substring(1);}
    @Override public void render(GuiGraphics g,int mx,int my,float delta){
        g.fill(0,0,width,height,0xF0101722);g.drawCenteredString(font,"Sound Detector - "+tab,width/2,12,0xFFF1F5FB);
        for(Label l:labels)g.drawString(font,font.plainSubstrByWidth(l.text,width/2-30),l.x,l.y,0xFFCCD8E6,false);
        g.drawString(font,font.plainSubstrByWidth(status,width-28),14,height-66,0xFF9FBCD3,false);
        super.render(g,mx,my,delta);
    }
    @Override public void onClose(){changed();minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
