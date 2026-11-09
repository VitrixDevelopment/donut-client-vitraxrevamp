package dev.donut.client.spectrebuilder;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpectreBuilderTest {
    @TempDir Path directory;
    @Test void spongeVersionsPreserveOffsetsAndProperties() throws Exception {
        for(int version:new int[]{2,3}) {
            Path file=sponge(version,new byte[]{0,1},2);
            var schematic=SchematicReader.read(file);
            assertEquals(new Schematic.Pos(-3,4,9),schematic.regions().getFirst().min());
            assertEquals(1,schematic.blocks().size());
            assertEquals("minecraft:oak_stairs[facing=east,half=top]",schematic.blocks().getFirst().state());
            assertEquals(new Schematic.Pos(-2,4,9),schematic.blocks().getFirst().pos());
        }
    }
    @Test void malformedAndOversizedBlockDataFail() throws Exception {
        assertThrows(IOException.class,()->SchematicReader.read(sponge(3,new byte[]{0},2)));
        assertThrows(IOException.class,()->SchematicReader.read(sponge(2,new byte[]{0,7},2)));
        assertThrows(IOException.class,()->SchematicReader.read(sponge(3,new byte[]{0,1,0},2)));
        assertThrows(IOException.class,()->SchematicReader.read(sponge(3,new byte[]{(byte)128,(byte)128,(byte)128,(byte)128,(byte)128,0},1)));
    }
    @Test void spanningLitematicWordsAndSignedRegionCoordinates() throws Exception {
        int[] ids=new int[40];long[] packed=new long[2];
        for(int i=0;i<ids.length;i++) {
            ids[i]=i%5;long value=ids[i],bit=(long)i*3;int word=(int)(bit/64),start=(int)(bit%64);
            packed[word]|=value<<start;if(start+3>64)packed[word+1]|=value>>>(64-start);
        }
        assertArrayEquals(ids,SchematicReader.unpack(packed,40,3,5));
        assertThrows(IOException.class,()->SchematicReader.unpack(new long[]{7},1,3,5));
        var region=new Schematic.Region("negative",new Schematic.Pos(-5,-2,-7),2,1,1,List.of("minecraft:stone"),new int[]{0,0});
        var schematic=new Schematic("test",0,List.of(region),Map.of());
        assertEquals(new Schematic.Pos(-5,-2,-7),schematic.blocks().getFirst().pos());
        assertEquals(new Schematic.Pos(7,-2,-5),schematic.blocks().getFirst().pos().rotate(1));
    }
    @Test void materialPackingUsesRealStackSizesAndSeparatesShulkers() {
        var plan=MaterialPlan.calculate(Map.of("stone",1729L,"slab",3L,"shulker",2L,"doorUpper",7L),state->switch(state){
            case "stone"->new MaterialPlan.Supply("stone",64,1,true);
            case "slab"->new MaterialPlan.Supply("slab",64,2,true);
            case "shulker"->new MaterialPlan.Supply("shulker",1,1,false);
            default->new MaterialPlan.Supply("door",64,0,true);
        });
        assertEquals(29,plan.storageSlots());assertEquals(2,plan.shulkers());assertTrue(plan.unresolved().isEmpty());
        var packing=SupplyDepotPlan.pack(plan);assertEquals(2,packing.boxes());
        assertEquals(29,packing.slots().size());assertEquals(2,packing.carrySeparately().getFirst().required());
        assertEquals(0,packing.slots().get(27).slot());assertEquals(1,packing.slots().get(27).box());
        assertEquals(1735,packing.slots().stream().mapToInt(SupplyDepotPlan.Slot::count).sum());
    }
    @Test void savedPlansRequireUnchangedFilesInsideTheLibrary() throws Exception {
        Path library=Files.createDirectory(directory.resolve("schematics")),jobs=directory.resolve("jobs"),file=library.resolve("test.schem");
        Files.writeString(file,"fixture");
        var plan=MaterialPlan.calculate(Map.of(),ignored->null);
        var job=BuildJobStore.create(file,"world",new Schematic.Pos(4,65,-8),3,plan);
        BuildJobStore.save(jobs,job);
        Path saved=jobs.resolve(job.id()+".json");
        assertEquals(job,BuildJobStore.load(saved,library));
        assertEquals("PLANNED",job.state());
        Files.writeString(file,"modified");assertThrows(IOException.class,()->BuildJobStore.load(saved,library));
        Path outside=directory.resolve("outside.schem");Files.writeString(outside,"outside");
        String escaped=Files.readString(saved).replace("test.schem","../outside.schem").replace(job.sha256(),BuildJobStore.hash(outside));
        Files.writeString(saved,escaped);assertThrows(IOException.class,()->BuildJobStore.load(saved,library));
    }
    private Path sponge(int version,byte[] data,int width) throws IOException {
        Path file=directory.resolve("fixture-"+version+".schem");
        try(var out=new DataOutputStream(Files.newOutputStream(file))){
            out.writeByte(10);out.writeUTF("");
            integer(out,"Version",version);integer(out,"DataVersion",4556);
            for(var e:Map.of("Width",width,"Height",1,"Length",1).entrySet()){out.writeByte(2);out.writeUTF(e.getKey());out.writeShort(e.getValue());}
            out.writeByte(11);out.writeUTF("Offset");out.writeInt(3);out.writeInt(-3);out.writeInt(4);out.writeInt(9);
            if(version==3){out.writeByte(10);out.writeUTF("Blocks");}
            out.writeByte(10);out.writeUTF("Palette");integer(out,"minecraft:air",0);integer(out,"minecraft:oak_stairs[facing=east,half=top]",1);out.writeByte(0);
            out.writeByte(7);out.writeUTF(version==3?"Data":"BlockData");out.writeInt(data.length);out.write(data);
            if(version==3)out.writeByte(0);out.writeByte(0);
        }
        return file;
    }
    private static void integer(DataOutputStream out,String name,int value)throws IOException {out.writeByte(3);out.writeUTF(name);out.writeInt(value);}
}
