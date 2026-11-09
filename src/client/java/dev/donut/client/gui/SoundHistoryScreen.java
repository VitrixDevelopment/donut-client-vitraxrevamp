package dev.donut.client.gui;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.sound.SoundDetectorController;
import dev.donut.client.sound.SoundIncident;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Bounded incident history with explicit actions rather than click interception on the HUD. */
public final class SoundHistoryScreen extends Screen {
    private final Screen parent;
    private final ClientConfig config;
    private EditBox search;
    private int offset;
    private long selectedId=-1;
    private String message="Select an incident to inspect its evidence.";
    private String query="";

    public SoundHistoryScreen(Screen parent,ClientConfig config){super(Component.literal("Sound activity history"));this.parent=parent;this.config=config;}
    private SoundDetectorController controller(){return SoundDetectorController.get();}
    @Override protected void init(){
        search=addRenderableWidget(new EditBox(font,14,34,Math.max(100,width-28),20,Component.literal("Filter history")));
        search.setMaxLength(100);search.setValue(query);search.setHint(Component.literal("Filter event, category, priority, or reason"));
        search.setResponder(v->{query=v;offset=0;selectedId=-1;});
        int count=7,bw=Math.max(40,Math.min(112,(width-28-4*(count-1))/count)),x=(width-(bw+4)*count+4)/2;
        addRenderableWidget(Button.builder(Component.literal("Acknowledge"),b->{var i=selected();if(i!=null&&controller()!=null)controller().acknowledge(i.id());}).bounds(x,height-28,bw,20).build());
        addRenderableWidget(Button.builder(Component.literal("Snooze 60s"),b->{var i=selected();if(i!=null&&controller()!=null){controller().muteSource(i.id(),60);message="Source snoozed for 60 seconds.";}}).bounds(x+(bw+4),height-28,bw,20).build());
        addRenderableWidget(Button.builder(Component.literal("Copy position"),b->{var i=selected();if(i!=null&&i.located()&&!config.soundDetector.hideCoordinates){minecraft.keyboardHandler.setClipboard(String.format(Locale.ROOT,"%.1f %.1f %.1f",i.x(),i.y(),i.z()));message="Position copied.";}else message="Position unavailable or hidden.";}).bounds(x+2*(bw+4),height-28,bw,20).build());
        addRenderableWidget(Button.builder(Component.literal("Export"),b->{try{message=controller()==null?"Detector not initialized.":"Saved "+controller().exportHistory().getFileName();}catch(Exception e){message="Could not export: "+e.getMessage();}}).bounds(x+3*(bw+4),height-28,bw,20).build());
        addRenderableWidget(Button.builder(Component.literal("Clear"),b->{if(controller()!=null)controller().clearHistory();selectedId=-1;offset=0;}).bounds(x+4*(bw+4),height-28,bw,20).build());
        addRenderableWidget(Button.builder(Component.literal("Inspect"),b->{var i=selected();if(i!=null)minecraft.setScreen(new SoundIncidentScreen(this,config,i));}).bounds(x+5*(bw+4),height-28,bw,20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"),b->onClose()).bounds(x+6*(bw+4),height-28,bw,20).build());
    }
    private List<SoundIncident> rows(){
        if(controller()==null)return List.of();
        String q=query.toLowerCase(Locale.ROOT);
        return controller().history().stream().filter(i->(i.label()+" "+i.category()+" "+i.reason()+" "+i.priority()+" "+i.actor()).toLowerCase(Locale.ROOT).contains(q)).toList();
    }
    private int rowCount(){return Math.max(1,(height-190)/28);}
    private SoundIncident selected(){return rows().stream().filter(i->i.id()==selectedId).findFirst().orElse(null);}
    @Override public void render(GuiGraphics g,int mx,int my,float delta){
        g.fill(0,0,width,height,0xED10151F);
        g.drawCenteredString(font,"Sound activity history",width/2,12,0xFFF0F4FB);
        var rows=rows();offset=Math.clamp(offset,0,Math.max(0,rows.size()-rowCount()));
        int y=62;
        for(int n=offset;n<Math.min(rows.size(),offset+rowCount());n++){
            var i=rows.get(n);int color=i.priority()==SoundIncident.Priority.CRITICAL?config.soundDetector.criticalColor:config.soundDetector.warningColor;
            g.fill(14,y,width-14,y+25,i.id()==selectedId?0xFF334454:0xFF1D2735);
            g.fill(14,y,17,y+25,0xFF000000|(color&0xFFFFFF));
            String text=i.priority()+"  "+i.label()+"  x"+i.count()+"  ["+i.category()+"]";
            g.drawString(font,font.plainSubstrByWidth(text,width-48),23,y+8,0xFFE5EAF1,false);y+=28;
        }
        if(rows.isEmpty())g.drawCenteredString(font,"No matching incidents. Received activity appears here while enabled.",width/2,76,0xFFB3BFCC);
        int detailY=height-116;
        g.fill(14,detailY-4,width-14,height-40,0xFF17212D);
        var selected=selected();
        if(selected!=null){
            var lines=SoundAlertHud.lines(selected,config);int lineY=detailY;
            for(String line:lines){if(lineY>height-56)break;g.drawString(font,font.plainSubstrByWidth(line,width-48),22,lineY,0xFFD1DDE9,false);lineY+=12;}
            String evidence="Evidence: "+selected.evidence().size()+" observations / score "+selected.score()+" points";
            g.drawString(font,font.plainSubstrByWidth(evidence,width-48),22,height-51,0xFF8FB2D1,false);
        }else g.drawString(font,font.plainSubstrByWidth(message,width-48),22,detailY,0xFFBCCADA,false);
        g.drawString(font,font.plainSubstrByWidth(message,width-28),14,height-40,0xFF9FBCD3,false);
        super.render(g,mx,my,delta);
    }
    @Override public boolean mouseClicked(MouseButtonEvent e,boolean doubled){
        if(super.mouseClicked(e,doubled))return true;
        if(e.button()==0&&e.x()>=14&&e.x()<width-14&&e.y()>=62&&e.y()<62+rowCount()*28){int index=offset+(int)(e.y()-62)/28;if(index<rows().size())selectedId=rows().get(index).id();return true;}
        return false;
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){offset=Math.clamp(offset-(int)(vertical*3),0,Math.max(0,rows().size()-rowCount()));return true;}
    @Override public boolean keyPressed(KeyEvent e){if(e.key()==GLFW.GLFW_KEY_DOWN||e.key()==GLFW.GLFW_KEY_UP){var entries=rows();if(entries.isEmpty())return true;int current=-1;for(int n=0;n<entries.size();n++)if(entries.get(n).id()==selectedId)current=n;int next=Math.clamp(current+(e.key()==GLFW.GLFW_KEY_DOWN?1:-1),0,entries.size()-1);selectedId=entries.get(next).id();offset=Math.clamp(next-rowCount()+1,0,Math.max(0,entries.size()-rowCount()));return true;}return super.keyPressed(e);}
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
