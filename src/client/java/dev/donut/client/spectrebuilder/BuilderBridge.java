// SPDX-License-Identifier: GPL-3.0-only
// Spectre 26.2 builder integration, based on 4ldenz's schematic / material tools.
package dev.donut.client.spectrebuilder;

import dev.donut.client.autobuild.LitematicLoader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Adapts Spectre's lossless reader and supply plans to Donut's existing preview and executor. */
public final class BuilderBridge {
    private BuilderBridge() { }
    public static String stateText(BlockState state) {
        String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        StringJoiner props=new StringJoiner(",");
        state.getProperties().stream().sorted(Comparator.comparing(Property::getName))
                .forEach(p->props.add(p.getName()+"="+propertyName(state,p)));
        return id+(state.getProperties().isEmpty()?"":"["+props+"]");
    }
    private static <T extends Comparable<T>> String propertyName(BlockState state,Property<T> property) {
        return property.getName(state.getValue(property));
    }
    public static dev.donut.client.autobuild.Schematic load(Path file) throws IOException {
        if(file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".litematic"))return LitematicLoader.load(file);
        try {
            Schematic source=SchematicReader.read(file);
            int minX=Integer.MAX_VALUE,minY=minX,minZ=minX,maxX=Integer.MIN_VALUE,maxY=maxX,maxZ=maxX;
            for(var region:source.regions()) {
                minX=Math.min(minX,region.min().x());minY=Math.min(minY,region.min().y());minZ=Math.min(minZ,region.min().z());
                maxX=Math.max(maxX,Math.addExact(region.min().x(),region.width()));
                maxY=Math.max(maxY,Math.addExact(region.min().y(),region.height()));
                maxZ=Math.max(maxZ,Math.addExact(region.min().z(),region.length()));
            }
            int w=Math.subtractExact(maxX,minX),h=Math.subtractExact(maxY,minY),l=Math.subtractExact(maxZ,minZ);
            if(w>65536||h>65536||l>65536)throw new IOException("Schematic bounds exceed 65536 blocks");
            List<dev.donut.client.autobuild.Schematic.Block> blocks=new ArrayList<>();
            for(var block:source.blocks()) {
                BlockState state=BuilderMaterials.state(block.state());
                if(!state.isAir())blocks.add(new dev.donut.client.autobuild.Schematic.Block(
                        new BlockPos(Math.subtractExact(block.pos().x(),minX),Math.subtractExact(block.pos().y(),minY),Math.subtractExact(block.pos().z(),minZ)),state,null));
            }
            if(blocks.isEmpty())throw new IOException("Schematic contains no non-air blocks");
            return new dev.donut.client.autobuild.Schematic(source.name(),blocks,new BlockPos(w,h,l),source.regions().size(),
                    List.of("Sponge offset normalized to the minimum corner. Position the preview before starting.",
                            "Block states are preserved. Container contents, entities and scheduled ticks are not constructed."));
        } catch(IllegalArgumentException|ArithmeticException e) {throw new IOException("Invalid schematic: "+e.getMessage(),e);}
    }
    public static MaterialPlan materials(dev.donut.client.autobuild.Schematic schematic) {
        Map<String,Long> counts=new TreeMap<>();
        for(var block:schematic.blocks())counts.merge(stateText(block.state()),1L,Long::sum);
        return MaterialPlan.calculate(counts,BuilderMaterials::supply);
    }
    public static String packingSummary(dev.donut.client.autobuild.Schematic schematic) {
        MaterialPlan materials=materials(schematic);SupplyDepotPlan packing=SupplyDepotPlan.pack(materials);
        StringBuilder text=new StringBuilder("SPECTRE MATERIAL PLAN\n\n");
        text.append(materials.storageSlots()).append(" storage slots / ").append(packing.boxes()).append(" shulker boxes\n");
        text.append("Full schematic quantities, before inventory credit. Boxes travel separately. Waterlogged blocks need a reusable water bucket.\n\n");
        for(var row:materials.rows())text.append(row.required()).append(" x ").append(row.item()).append(" / ").append((row.required()+row.maxStack()-1)/row.maxStack()).append(" stacks\n");
        text.append("\nSHULKER PACKING\n");
        int shown=0;
        for(var slot:packing.slots()) {
            if(shown++==500){text.append("Further slots omitted from this preview. Save the plan to retain all quantities.\n");break;}
            text.append("Box ").append(slot.box()+1).append(" / slot ").append(slot.slot()+1).append(": ").append(slot.count()).append(" x ").append(slot.item()).append('\n');
        }
        if(!packing.carrySeparately().isEmpty()) {
            text.append("\nCARRY SEPARATELY\n");
            for(var row:packing.carrySeparately())text.append(row.required()).append(" x ").append(row.item()).append('\n');
        }
        if(!materials.unresolved().isEmpty())text.append("\nMANUAL SETUP\n").append(String.join("\n",materials.unresolved()));
        return text.toString();
    }
}
