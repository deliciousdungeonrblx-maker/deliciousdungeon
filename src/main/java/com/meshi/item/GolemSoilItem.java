package com.meshi.item;

import com.meshi.crop.GolemCrop;
import com.meshi.entity.SoilMoundEntity;
import com.meshi.registry.ModEntities;
import com.meshi.registry.ModItems;
import com.meshi.util.Fx;
import java.util.List;
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

    public static ItemStack create(int seeds, GolemCrop crop) {
        ItemStack stack = new ItemStack(ModItems.GOLEM_SOIL.get());
        if (seeds > 0 && crop != null) {
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
                tag.putInt("Seeds", seeds);
                tag.putString("Crop", crop.id());
            });
        }
        return stack;
    }

    private static CompoundTag dataOf(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    public static int seedsOf(ItemStack stack) {
        return Math.max(0, Math.min(SoilMoundEntity.MAX_SEEDS, dataOf(stack).getInt("Seeds")));
    }

    public static GolemCrop cropOf(ItemStack stack) {
        return GolemCrop.byId(dataOf(stack).getString("Crop"));
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
        mound.setContents(seedsOf(stack), cropOf(stack));
        mound.startTimer(server.getGameTime());
        server.addFreshEntity(mound);

        Fx.dirtBurst(server, at.x, at.y + 0.2D, at.z, 18, 0.7D, 0.05D);
        Fx.sound(server, at.x, at.y, at.z, SoundEvents.GRAVEL_PLACE, 1.0F, 0.7F);

        Player player = context.getPlayer();
        if (player == null || !player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        return InteractionResult.sidedSuccess(false);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        int seeds = seedsOf(stack);
        GolemCrop crop = cropOf(stack);
        if (seeds > 0 && crop != null) {
            tooltip.add(Component.literal("Crop: " + crop.displayName()).withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.literal("Seeds: " + seeds + "/" + SoilMoundEntity.MAX_SEEDS).withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.literal("No seeds planted").withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
