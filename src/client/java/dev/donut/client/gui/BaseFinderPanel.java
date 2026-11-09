// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Spectre 26.2 by 4ldenz. See docs/spectre-base-finder-port.md.
package dev.donut.client.gui;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.chunkfinder.ChunkFinderScanner;
import dev.donut.client.mixin.BaseFinderProjectionAccessor;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.*;
import java.util.function.ToIntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.*;
import dev.donut.client.chunkfinder.SuspectObjects;
import dev.donut.client.chunkfinder.SuspectObjects.Category;

/** Screen-projected look card; readable size does not shrink with world distance. */
public final class BaseFinderPanel {
    private static final net.minecraft.network.chat.FontDescription PANEL_FONT = new net.minecraft.network.chat.FontDescription.Resource(
            net.minecraft.resources.Identifier.fromNamespaceAndPath("donut","workspace"));
    private static final float TYPE_SCALE=.5625f;
    private static net.minecraft.network.chat.Component label(String text) {
        return net.minecraft.network.chat.Component.literal(text).withStyle(style->style.withFont(PANEL_FONT));
    }
    private static int panelWidth(net.minecraft.client.gui.Font font,String text) {
        return (int)Math.ceil(font.width(label(text))*TYPE_SCALE);
    }
    private static String trim(net.minecraft.client.gui.Font font,String text,int width) {
        while(!text.isEmpty()&&panelWidth(font,text)>width) text=text.substring(0,text.offsetByCodePoints(text.length(),-1));
        return text;
    }
    private static void panelText(GuiGraphics g,net.minecraft.client.gui.Font font,String text,int x,int y,int color,boolean shadow) {
        g.pose().pushMatrix();
        g.pose().translate(x,y);g.pose().scale(TYPE_SCALE,TYPE_SCALE);
        g.drawString(font,label(text),0,0,color,shadow);
        g.pose().popMatrix();
    }

    private static Matrix4f viewProjection;
    private static Vec3 cameraPosition;
    private static java.lang.ref.WeakReference<ClientLevel> capturedLevel = new java.lang.ref.WeakReference<>(null);

    static float fittedScale(int guiWidth, int guiHeight, int panelHeight, double guiScale, double uiScale) {
        float desired = (float) (Math.max(1, 2 / guiScale) * uiScale);
        return Math.min(desired, Math.min(Math.max(1, guiWidth - 16) / 254f,
                Math.max(1, guiHeight - 44) / (float) panelHeight));
    }

    public static void captureProjection(WorldExtractionContext context) {
        var mc = Minecraft.getInstance();
        float delta = context.tickCounter().getGameTimeDeltaPartialTick(false);
        float fov = ((BaseFinderProjectionAccessor) mc.gameRenderer).donut$worldFov(context.camera(), delta, true);
        viewProjection = mc.gameRenderer.getProjectionMatrix(fov).mul(context.viewMatrix());
        cameraPosition = context.camera().position();
        if (capturedLevel.get() != context.world()) capturedLevel = new java.lang.ref.WeakReference<>(context.world());
    }

    public static void render(GuiGraphics graphics,ClientConfig config,ChunkFinderScanner scanner) {
        var mc = Minecraft.getInstance();
        if (!config.chunkFinder.enabled || !config.chunkFinder.inspectPanel || mc.level == null
                || mc.player == null || mc.screen != null || mc.options.hideGui) return;
        var camera = mc.gameRenderer.getMainCamera();
        var forward = camera.forwardVector();
        var focus = SuspectObjects.focus(scanner.snapshot(),camera.position(),
                new Vec3(forward.x(),forward.y(),forward.z()),config.chunkFinder.objectRange);
        if (focus != null) draw(graphics,focus,config.chunkFinder::categoryColor,scanner.detailLimited(),config);
    }

    private static float[] project(Minecraft mc,Vec3 world,float width,float height) {
        if (viewProjection == null || capturedLevel.get() != mc.level) return null;
        Vec3 relative = world.subtract(cameraPosition);
        var clip = viewProjection.transform(new Vector4f((float)relative.x,(float)relative.y,(float)relative.z,1));
        if (!Float.isFinite(clip.w) || clip.w <= 0) return null;
        float x = (clip.x / clip.w + 1) * .5f * width;
        float y = (1 - clip.y / clip.w) * .5f * height;
        return Float.isFinite(x) && Float.isFinite(y) && x >= -200 && x <= width+200 && y >= -100 && y <= height+100
                ? new float[]{x,y} : null;
    }

    public static void draw(GuiGraphics g,SuspectObjects.Focus focus,ToIntFunction<Category> colors,boolean limited,ClientConfig config){
        var mc=Minecraft.getInstance();var font=mc.font;
        var counts=new EnumMap<Category,Map<SuspectObjects.Icon,Integer>>(Category.class);
        for(var marker:focus.nearby())marker.icons().forEach((item,n)->counts.computeIfAbsent(marker.category(),k->new HashMap<>()).merge(item,n,Integer::sum));
        record Slot(SuspectObjects.Icon icon,int count,Category category){}
        var slots=new ArrayList<Slot>();
        counts.forEach((category,items)->items.entrySet().stream().sorted(Comparator.comparing(e->e.getKey().label())).forEach(e->slots.add(new Slot(e.getKey(),e.getValue(),category))));
        int width=254,columns=7,shown=Math.min(28,slots.size()),rows=(shown+columns-1)/columns;
        var detailLines=focus.target().detail().split("\n");int extraDetail=(detailLines.length-1)*12;
        int legendRows=(counts.size()+1)/2,height=78+extraDetail+rows*32+legendRows*14+25;
        float scale=fittedScale(g.guiWidth(),g.guiHeight(),height,mc.getWindow().getGuiScale(),config.clickGuiStyle.scale);
        var box=focus.target().bounds();
        var anchor=project(mc,new net.minecraft.world.phys.Vec3(box.getCenter().x,box.maxY+.35,box.getCenter().z),g.guiWidth(),g.guiHeight());
        if(anchor==null)anchor=project(mc,box.getCenter(),g.guiWidth(),g.guiHeight());
        if(anchor==null){
            var camera=mc.gameRenderer.getMainCamera();var direction=camera.forwardVector();
            var hit=box.clip(camera.position(),camera.position().add(direction.x()*512,direction.y()*512,direction.z()*512));
            if(hit.isPresent())anchor=project(mc,hit.get(),g.guiWidth(),g.guiHeight());
        }
        if(anchor==null)return;
        float x,y;
        if(anchor[0]+20+width*scale<g.guiWidth()-8){x=anchor[0]+20;y=anchor[1]-height*scale*.35F;}
        else if(anchor[0]-20-width*scale>8){x=anchor[0]-20-width*scale;y=anchor[1]-height*scale*.35F;}
        else{x=anchor[0]-width*scale*.5F;y=anchor[1]-height*scale-12;if(y<36)y=anchor[1]+12;}
        x=Math.clamp(x,8,Math.max(8,g.guiWidth()-width*scale-8));
        y=Math.clamp(y,36,Math.max(36,g.guiHeight()-height*scale-8));
        int accent=(0xFF000000|config.accentColor),card=0x4A343746;
        var style=config.chunkFinder;
        int panelAlpha=(int)Math.round(style.panelOpacity*255)<<24;
        int ax=Math.round(anchor[0]),ay=Math.round(anchor[1]);
        int edgeX=Math.round(Math.clamp(anchor[0],x,x+width*scale)),edgeY=Math.round(Math.clamp(anchor[1],y,y+height*scale));
        g.fill(Math.min(ax,edgeX),ay,Math.max(ax,edgeX)+1,ay+1,accent);
        g.fill(edgeX,Math.min(ay,edgeY),edgeX+1,Math.max(ay,edgeY)+1,accent);g.fill(ax-2,ay-2,ax+2,ay+2,accent);
        var pose=g.pose();pose.pushMatrix();pose.translate(x,y);pose.scale(scale,scale);
        int radius=7;
        UiPaint.shadow(g,0,2,width,height,radius,0x70000000);
        GlassPanel.drawGradient(g,0,0,width,height,radius,panelAlpha|style.panelTopColor,panelAlpha|style.panelBottomColor,.94f);
        UiPaint.outline(g,0,0,width,height,radius,0x395B6078);
        g.fill(12,12,14,25,accent);panelText(g,font,"DONUT / DISCOVERIES",21,14,0xFFE9ECF5,false);
        String distance=String.format(Locale.ROOT,"%.0fm",focus.distance());
        panelText(g,font,distance,width-12-panelWidth(font,distance),14,accent,false);
        String label=trim(font,focus.target().label(),width-24);
        panelText(g,font,label,12,33,colors.applyAsInt(focus.target().category()),false);
        g.fill(12,29,width-12,30,0x284F5264);
        for(int i=0;i<detailLines.length;i++)panelText(g,font,trim(font,detailLines[i],width-24),12,46+i*12,0xFFD8DDE8,false);
        var signals=new HashSet<String>();for(var m:focus.nearby())signals.add(m.category()==Category.GROWTH?m.label():m.category().label);
        panelText(g,font,signals.size()+" clue types together / nearby objects",12,60+extraDetail,0xFFADBCC9,false);
        for(int i=0;i<shown;i++){
            var slot=slots.get(i);int sx=12+(i%columns)*33,sy=76+extraDetail+(i/columns)*32;
            rounded(g,sx,sy,sx+30,sy+29,card,3);g.fill(sx+3,sy+26,sx+27,sy+28,colors.applyAsInt(slot.category));
            if(slot.icon.texture()!=null)g.blit(RenderPipelines.GUI_TEXTURED,slot.icon.texture(),sx+6,sy+2,0,0,16,16,16,16);
            else g.renderItem(new ItemStack(slot.icon.item()),sx+6,sy+2);
            String count=String.valueOf(slot.count);panelText(g,font,count,sx+28-panelWidth(font,count),sy+16,0xFFFFFFFF,true);
        }
        int ly=79+extraDetail+rows*32,n=0;
        for(var entry:counts.entrySet()){
            int lx=12+(n%2)*118,yy=ly+(n/2)*14;n++;
            g.fill(lx,yy+2,lx+4,yy+8,colors.applyAsInt(entry.getKey()));
            int count=entry.getValue().values().stream().mapToInt(Integer::intValue).sum();
            String text=count+" "+entry.getKey().label;
            panelText(g,font,trim(font,text,109),lx+8,yy,0xFFE3EBF2,false);
        }
        String footer=slots.size()>shown?"+"+(slots.size()-shown)+" more item types":limited?"Detail limit reached - move closer":"Loaded objects / not proof of a base";
        g.fill(12,height-22,width-12,height-21,0x284F5264);
        panelText(g,font,footer,12,height-15,0xFFA5ABB9,false);pose.popMatrix();
    }
    private static void rounded(GuiGraphics g,int x,int y,int right,int bottom,int color,int radius){
        UiPaint.round(g,x,y,right-x,bottom-y,radius,color);
    }
}
