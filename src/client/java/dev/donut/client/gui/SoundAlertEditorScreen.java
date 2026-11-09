package dev.donut.client.gui;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.input.ModuleKeybinds;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Synthetic notification preview; never submits detector observations or plays a sound. */
public final class SoundAlertEditorScreen extends Screen {
    private final Screen parent;
    private final ClientConfig config;
    private final Runnable save;
    private boolean critical, dragging;
    private double grabX, grabY;

    public SoundAlertEditorScreen(Screen parent, ClientConfig config, Runnable save, boolean critical) {
        super(Component.literal("Sound alert preview"));
        this.parent=parent; this.config=config; this.save=save; this.critical=critical;
    }

    @Override protected void init() {
        dragging=false;
        String[] anchors={"TL","Top","TR","Left","Center","Right","BL","Bottom","BR"};
        int aw=Math.max(24,Math.min(42,(width-40)/9)),ax=(width-(aw+2)*9+2)/2;
        for(int n=0;n<anchors.length;n++){
            int anchor=n;
            addRenderableWidget(Button.builder(Component.literal(anchors[n]),b->{config.soundDetector.x=(anchor%3)/2.0;config.soundDetector.y=(anchor/3)/2.0;save.run();})
                    .bounds(ax+n*(aw+2),height-57,aw,20).build());
        }
        int bw=Math.min(130,(width-32)/3);
        addRenderableWidget(Button.builder(Component.literal(critical?"Show warning":"Show critical"),b->{critical=!critical;rebuildWidgets();})
                .bounds(width/2-bw*3/2-4,height-31,bw,20).build());
        addRenderableWidget(Button.builder(Component.literal("Reset position"),b->{config.soundDetector.x=.5;config.soundDetector.y=.06;save.run();})
                .bounds(width/2-bw/2,height-31,bw,20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"),b->onClose())
                .bounds(width/2+bw/2+4,height-31,bw,20).build());
    }
    @Override public void render(GuiGraphics g,int mx,int my,float delta) {
        g.fill(0,0,width,height,0x30060A12);
        if(dragging) { g.fill(width/2,0,width/2+1,height,0x307A91B8);g.fill(0,height/2,width,height/2+1,0x307A91B8); }
        SoundAlertHud.preview(g,config,critical);
        var b=SoundAlertHud.layout(width,height,config);
        if(dragging||b.contains(mx,my)) UiPaint.outline(g,b.x(),b.y(),b.width(),b.height(),8,0x90D7EDFF);
        g.drawCenteredString(font,"Preview only - drag / arrows adjust / choose an anchor",width/2,height-75,0xFFE0E7F1);
        super.render(g,mx,my,delta);
    }
    @Override public boolean mouseClicked(MouseButtonEvent e,boolean doubled) {
        if(super.mouseClicked(e,doubled))return true;
        var b=SoundAlertHud.layout(width,height,config);
        if(e.button()!=0||!b.contains(e.x(),e.y()))return false;
        dragging=true;grabX=e.x()-b.x();grabY=e.y()-b.y();return true;
    }
    @Override public boolean mouseDragged(MouseButtonEvent e,double dx,double dy) {
        if(!dragging)return super.mouseDragged(e,dx,dy);
        SoundAlertHud.moveTo(config,width,height,e.x()-grabX,e.y()-grabY);return true;
    }
    @Override public boolean mouseReleased(MouseButtonEvent e) {
        if(!dragging)return super.mouseReleased(e);
        dragging=false;save.run();return true;
    }
    @Override public boolean keyPressed(KeyEvent e) {
        int dx=e.key()==GLFW.GLFW_KEY_LEFT?-1:e.key()==GLFW.GLFW_KEY_RIGHT?1:0;
        int dy=e.key()==GLFW.GLFW_KEY_UP?-1:e.key()==GLFW.GLFW_KEY_DOWN?1:0;
        if(dx!=0||dy!=0){int step=(e.modifiers()&GLFW.GLFW_MOD_SHIFT)!=0?10:1;var b=SoundAlertHud.layout(width,height,config);
            SoundAlertHud.moveTo(config,width,height,b.x()+dx*step,b.y()+dy*step);save.run();return true;}
        return super.keyPressed(e);
    }
    @Override public void onClose(){save.run();minecraft.setScreen(parent);}
    @Override public void removed(){dragging=false;save.run();ModuleKeybinds.suppressUntilReleased(minecraft);}
    @Override public boolean isPauseScreen(){return false;}
}
