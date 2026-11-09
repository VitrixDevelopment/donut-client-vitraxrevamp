package dev.donut.client.light;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class LightDebugTest {
    @Test void denoiseRemovesWeakIsolatedFieldsAndKeepsStrongerSources() {
        var hits=new HashMap<BlockPos,Integer>();
        hits.put(BlockPos.ZERO,7);hits.put(new BlockPos(1,0,0),6);hits.put(new BlockPos(2,0,0),5);
        assertEquals(3,count(LightDebugScanner.buildBoxes(hits,false,5)));
        assertEquals(0,count(LightDebugScanner.buildBoxes(hits,true,5)));
        hits.put(new BlockPos(-1,0,0),14);
        assertEquals(4,count(LightDebugScanner.buildBoxes(hits,true,5)));
    }
    @Test void onlyExposedCellsAreDrawnAndGeometryIsBounded() {
        var hits=new LinkedHashMap<BlockPos,Integer>();
        for(int x=0;x<3;x++)for(int y=0;y<3;y++)for(int z=0;z<3;z++)hits.put(new BlockPos(x,y,z),15);
        assertEquals(26,count(LightDebugScanner.buildBoxes(hits,false,1)));
        hits.clear();for(int i=0;i<13000;i++)hits.put(new BlockPos(i*2,-1,0),15);
        assertEquals(12000,count(LightDebugScanner.buildBoxes(hits,false,1)));
        var boxes=LightDebugScanner.buildBoxes(Map.of(BlockPos.ZERO,15),false,1);
        assertThrows(UnsupportedOperationException.class,()->boxes.clear());
        assertThrows(UnsupportedOperationException.class,()->boxes.get(15).clear());
    }
    @Test void brighterCellsBecomeWhiterAndMoreOpaque() {
        assertEquals(0x80FFFFFF,LightDebugScanner.fillColor(0x80FF0000,15));
        int low=LightDebugScanner.fillColor(0x80FF0000,5),high=LightDebugScanner.fillColor(0x80FF0000,12);
        assertTrue((low>>>24)<(high>>>24));assertTrue((low&255)<(high&255));
        assertEquals(0xFF737373,LightDebugScanner.strokeColor(0x80FFFFFF));
    }
    private static int count(Map<Integer,? extends List<?>> boxes){return boxes.values().stream().mapToInt(List::size).sum();}
}
