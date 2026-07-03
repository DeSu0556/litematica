package fi.dy.masa.litematica.materials;

import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.position.LayerRange;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.SchematicMetadata;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement.RequiredEnabled;
import fi.dy.masa.litematica.tool.ToolMode;
import fi.dy.masa.litematica.util.BlockInfoListType;
import fi.dy.masa.litematica.util.BlockUtils;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.util.SchematicUtils;

public class MaterialListSchematicReplacer
{
    public static boolean canReplaceEntry(MaterialListBase materialList, @Nullable MaterialListEntry entry)
    {
        return entry != null &&
               materialList instanceof MaterialListPlacement &&
               DataManager.getToolMode() == ToolMode.REBUILD &&
               isPureBlockItem(entry.getStack());
    }

    public static boolean replaceMaterialWithHeldBlock(MaterialListPlacement materialList, MaterialListEntry entry)
    {
        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null || DataManager.getToolMode() != ToolMode.REBUILD)
        {
            return false;
        }

        ItemStack materialStack = entry.getStack();

        if (isPureBlockItem(materialStack) == false)
        {
            InfoUtils.showGuiOrInGameMessage(MessageType.WARNING, "litematica.message.warn.no_schematic_blocks_replaced");
            return false;
        }

        ItemStack heldStack = mc.player.getMainHandItem();

        if (heldStack.isEmpty() || isPureBlockItem(heldStack) == false)
        {
            InfoUtils.showGuiOrInGameMessage(MessageType.ERROR, "litematica.message.error.must_hold_block_item");
            return false;
        }

        SchematicPlacement placement = materialList.getPlacement();

        if (placement.isLocked())
        {
            InfoUtils.showGuiOrActionBarMessage(MessageType.ERROR, "litematica.message.placement.cant_modify_is_locked");
            return false;
        }

        Block sourceBlock = ((BlockItem) materialStack.getItem()).getBlock();
        BlockState replacementState = ((BlockItem) heldStack.getItem()).getBlock().defaultBlockState();
        int replaced = replaceMaterialBlocks(placement, sourceBlock, replacementState, materialList.getMaterialListType());

        if (replaced <= 0)
        {
            InfoUtils.showGuiOrInGameMessage(MessageType.WARNING, "litematica.message.warn.no_schematic_blocks_replaced");
            return false;
        }

        DataManager.getSchematicPlacementManager().markAllPlacementsOfSchematicForRebuild(placement.getSchematic());
        InfoUtils.showGuiOrActionBarMessage(MessageType.SUCCESS, "litematica.message.schematic_blocks_replaced_with_held_block", replaced);

        return true;
    }

    private static int replaceMaterialBlocks(SchematicPlacement placement, Block sourceBlock, BlockState replacementStateIn, BlockInfoListType listType)
    {
        Minecraft mc = Minecraft.getInstance();
        int replaced = 0;
        LitematicaSchematic schematic = placement.getSchematic();

        for (String regionName : placement.getSubRegionBoxes(RequiredEnabled.PLACEMENT_ENABLED).keySet())
        {
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(regionName);

            if (container == null)
            {
                continue;
            }

            BlockState replacementState = SchematicUtils.getUntransformedBlockState(replacementStateIn, placement, regionName);
            Vec3i size = container.getSize();
            int startX = 0;
            int startY = 0;
            int startZ = 0;
            int endX = size.getX() - 1;
            int endY = size.getY() - 1;
            int endZ = size.getZ() - 1;

            if (listType == BlockInfoListType.RENDER_LAYERS && mc.level != null)
            {
                LayerRange range = DataManager.getRenderLayerRange();
                int minX = range.getClampedValue(-30000000, Direction.Axis.X);
                int minZ = range.getClampedValue(-30000000, Direction.Axis.Z);
                int maxX = range.getClampedValue( 30000000, Direction.Axis.X);
                int maxZ = range.getClampedValue( 30000000, Direction.Axis.Z);
                int minY = range.getClampedValue(mc.level.getMinY(), Direction.Axis.Y);
                int maxY = range.getClampedValue(mc.level.getMaxY(), Direction.Axis.Y);

                BlockPos pos1 = SchematicUtils.getReverserTransformedWorldPosition(new BlockPos(minX, minY, minZ), schematic,
                        regionName, placement, placement.getRelativeSubRegionPlacement(regionName));
                BlockPos pos2 = SchematicUtils.getReverserTransformedWorldPosition(new BlockPos(maxX, maxY, maxZ), schematic,
                        regionName, placement, placement.getRelativeSubRegionPlacement(regionName));

                if (pos1 == null || pos2 == null)
                {
                    continue;
                }

                BlockPos posMin = PositionUtils.getMinCorner(pos1, pos2);
                BlockPos posMax = PositionUtils.getMaxCorner(pos1, pos2);
                startX = Math.max(posMin.getX(), 0);
                startY = Math.max(posMin.getY(), 0);
                startZ = Math.max(posMin.getZ(), 0);
                endX = Math.min(posMax.getX(), size.getX() - 1);
                endY = Math.min(posMax.getY(), size.getY() - 1);
                endZ = Math.min(posMax.getZ(), size.getZ() - 1);
            }

            Map<BlockPos, CompoundTag> blockEntities = schematic.getBlockEntityMapForRegion(regionName);

            for (int y = startY; y <= endY; ++y)
            {
                for (int z = startZ; z <= endZ; ++z)
                {
                    for (int x = startX; x <= endX; ++x)
                    {
                        BlockState oldState = container.get(x, y, z);

                        if (canReplaceState(oldState, sourceBlock, replacementState))
                        {
                            BlockState newState = copyProperties(oldState, replacementState);

                            if (oldState != newState)
                            {
                                container.set(x, y, z, newState);
                            }

                            if (blockEntities != null && blockEntities.isEmpty() == false)
                            {
                                blockEntities.remove(new BlockPos(x, y, z));
                            }

                            ++replaced;
                        }
                    }
                }
            }
        }

        if (replaced > 0)
        {
            SchematicMetadata metadata = schematic.getMetadata();
            metadata.setTimeModifiedToNow();
            metadata.setModifiedSinceSaved();
        }

        return replaced;
    }

    private static boolean canReplaceState(BlockState oldState, Block sourceBlock, BlockState replacementState)
    {
        return oldState.getBlock() == sourceBlock &&
               oldState.hasBlockEntity() == false &&
               replacementState.hasBlockEntity() == false &&
               BlockUtils.blocksHaveSameProperties(oldState, replacementState);
    }

    private static boolean isPureBlockItem(ItemStack stack)
    {
        return stack.getItem() instanceof BlockItem blockItem &&
               blockItem.getBlock().defaultBlockState().hasBlockEntity() == false;
    }

    private static BlockState copyProperties(BlockState source, BlockState target)
    {
        BlockState result = target;

        for (Property<?> prop : source.getProperties())
        {
            result = BlockUtils.getBlockStateWithProperty(result, prop, source.getValue(prop));
        }

        return result;
    }
}
