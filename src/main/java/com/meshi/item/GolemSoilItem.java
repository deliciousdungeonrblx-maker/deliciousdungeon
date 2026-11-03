package com.meshi.item;

import com.meshi.crop.GolemCrop;
import com.meshi.entity.SoilMoundEntity;
import com.meshi.registry.ModEntities;
import com.meshi.registry.ModItems;
import com.meshi.util.Fx;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * What you hold after picking up a mound. It remembers how many seeds were planted and which crop,
 * and right-clicking the top of a block puts the mound back down (and restarts its one-day timer).
 */
public class GolemSoilItem extends Item {
    public GolemSoilItem(Properties properties) {
        super(properties);
    }

    /** A Golem Soil item that remembers every seed planted, in any mix of crops. */
    public static ItemStack create(List<GolemCrop> crops, long growTicks) {
        ItemStack stack = new ItemStack(ModItems.GOLEM_SOIL.get());
        boolean customTime = growTicks != SoilMoundEntity.GROW_TICKS;
        if (!crops.isEmpty() || customTime) {
            List<GolemCrop> copy = new ArrayList<>(crops);
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
                if (!copy.isEmpty()) {
                    GolemCrop.writeList(tag, copy);
                }
                if (customTime) {
                    tag.putLong("GrowTicks", growTicks);
                }
            });
        }
        return stack;
    }

    /** The grow time stored on the item (one day if it has no custom time). */
    public static long growTicksOf(ItemStack stack) {
        CompoundTag tag = dataOf(stack);
        long ticks = tag.contains("GrowTicks") ? tag.getLong("GrowTicks") : SoilMoundEntity.GROW_TICKS;
        return ticks > 0 ? ticks : SoilMoundEntity.GROW_TICKS;
    }

    private static CompoundTag dataOf(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    /** The planted crops stored on the item, one entry per seed (old one-crop items still read fine). */
    public static List<GolemCrop> cropsOf(ItemStack stack) {
        return GolemCrop.readList(dataOf(stack), SoilMoundEntity.MAX_SEEDS);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        // Only on top of a block, with room for the mound.
        if (context.getClickedFace() != Direction.UP) {
            return InteractionResult.FAIL;
        }
        BlockPos pos = context.getClickedPos().above();
        if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
            return InteractionResult.FAIL;
        }
        if (!(level instanceof ServerLevel server)) {
            return InteractionResult.SUCCESS;
        }

        ItemStack stack = context.getItemInHand();
        SoilMoundEntity mound = ModEntities.SOIL_MOUND.get().create(server);
        if (mound == null) {
            return InteractionResult.FAIL;
        }

        Vec3 at = Vec3.atBottomCenterOf(pos);
        mound.setPos(at.x, at.y, at.z);
        float yaw = context.getRotation();
        mound.setYRot(yaw);
        mound.yRotO = yaw;
        List<GolemCrop> crops = cropsOf(stack);
        mound.setCrops(crops);
        mound.setGrowTicks(growTicksOf(stack));
        mound.startTimer(server.getGameTime());
        server.addFreshEntity(mound);

        Fx.dirtBurst(server, at.x, at.y + 0.2D, at.z, 24, 0.8D, 0.06D);
        Fx.shockwave(server, Fx.dirt(), at.x, at.y + 0.1D, at.z, 1.5D);
        Fx.dust(server, at.x, at.y + 0.3D, at.z, 6, 0.6D);
        if (!crops.isEmpty()) {
            Fx.sparkle(server, at.x, at.y + 0.5D, at.z, 6 + 2 * crops.size(), 0.8D);
        }
        Fx.sound(server, at.x, at.y, at.z, SoundEvents.GRAVEL_PLACE, 1.0F, 0.7F);
        Fx.sound(server, at.x, at.y, at.z, SoundEvents.ROOTED_DIRT_PLACE, 1.0F, 0.7F);

        Player player = context.getPlayer();
        if (player == null || !player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        return InteractionResult.sidedSuccess(false);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        List<GolemCrop> crops = cropsOf(stack);
        if (!crops.isEmpty()) {
            // "Cabbage x2, Tomato x1" in the order they were first planted.
            Map<GolemCrop, Integer> counts = new LinkedHashMap<>();
            for (GolemCrop crop : crops) {
                counts.merge(crop, 1, Integer::sum);
            }
            StringBuilder line = new StringBuilder();
            for (Map.Entry<GolemCrop, Integer> entry : counts.entrySet()) {
                if (line.length() > 0) {
                    line.append(", ");
                }
                line.append(entry.getKey().displayName()).append(" x").append(entry.getValue());
            }
            tooltip.add(Component.literal("Crops: " + line).withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.literal("Seeds: " + crops.size() + "/" + SoilMoundEntity.MAX_SEEDS).withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.literal("No seeds planted").withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
