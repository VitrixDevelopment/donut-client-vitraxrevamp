package dev.donut.client.gui;

import dev.donut.client.config.ClientConfig;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SoundAlertLayoutTest {
    @Test void everyAnchorAndGuiSizeStaysInsideViewport() {
        var config=new ClientConfig();
        for(int[] size:new int[][]{{320,240},{640,360},{960,540},{1920,1080}})
            for(double x:new double[]{0,.5,1})for(double y:new double[]{0,.5,1})for(double scale:new double[]{.5,1,2}){
                config.soundDetector.x=x;config.soundDetector.y=y;config.soundDetector.scale=scale;
                var b=SoundAlertHud.layout(size[0],size[1],config);
                assertTrue(b.x()>=6&&b.y()>=6);
                assertTrue(b.width()>0&&b.height()>0);
                assertTrue(b.x()+b.width()<=size[0]-6);
                assertTrue(b.y()+b.height()<=size[1]-6);
            }
    }
    @Test void optionalContentGetsSpaceAndCompactModeReducesIt() {
        var config=new ClientConfig();var s=config.soundDetector;
        s.showCoordinates=false;s.showAge=false;s.showReason=false;
        int basic=SoundAlertHud.layout(1280,800,config).height();
        s.showCoordinates=true;s.showAge=true;s.showReason=true;
        int expanded=SoundAlertHud.layout(1280,800,config).height();
        assertEquals(36,expanded-basic);
        s.compact=true;
        assertTrue(SoundAlertHud.layout(1280,800,config).height()<basic);
        s.compact=false;s.hideCoordinates=true;
        assertEquals(expanded-12,SoundAlertHud.layout(1280,800,config).height());
    }
    @Test void dragNormalizesAndClampsWithoutBreakingAfterResize() {
        var config=new ClientConfig();
        SoundAlertHud.moveTo(config,1280,800,-500,2000);
        assertEquals(0,config.soundDetector.x);assertEquals(1,config.soundDetector.y);
        var b=SoundAlertHud.layout(640,360,config);
        assertEquals(6,b.x());assertEquals(354,b.y()+b.height());
        SoundAlertHud.moveTo(config,640,360,100,100);
        var moved=SoundAlertHud.layout(640,360,config);
        assertEquals(100,moved.x());assertEquals(100,moved.y());
    }
}
