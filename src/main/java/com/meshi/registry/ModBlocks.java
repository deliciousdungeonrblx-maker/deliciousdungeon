package com.meshi.registry;

import com.meshi.Meshi;
import com.meshi.block.FireTrapPlateBlock;
import com.meshi.block.HotpotBlock;
import com.meshi.block.ScorpionBurrowBlock;
import com.meshi.block.TrapFloorBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Meshi.MODID);

    public static final DeferredBlock<ScorpionBurrowBlock> SCORPION_BURROW =
            BLOCKS.register("scorpion_burrow", () -> new ScorpionBurrowBlock(
                    BlockBehaviour.Properties.of()
                            .strength(0.4F)
                            .sound(SoundType.STONE)
                            .noCollission()
                            .noOcclusion()
                            .noLootTable()
                            .pushReaction(PushReaction.DESTROY)));

    // same props as mud bricks so they feel identical
    public static final DeferredBlock<TrapFloorBlock> SLIME_TRAP_PLATE =
            BLOCKS.register("slime_trap_plate", () -> new TrapFloorBlock(trapProps(), TrapFloorBlock.Kind.SLIME));

    public static final DeferredBlock<TrapFloorBlock> RANDOM_TRAP_PLATE =
            BLOCKS.register("random_trap_plate", () -> new TrapFloorBlock(trapProps(), TrapFloorBlock.Kind.RANDOM));

    public static final DeferredBlock<TrapFloorBlock> ARROW_TRAP_PLATE =
            BLOCKS.register("arrow_trap_plate", () -> new TrapFloorBlock(trapProps(), TrapFloorBlock.Kind.ARROWS));

    // burns while stood on, see FireTrapPlateBlock
    public static final DeferredBlock<FireTrapPlateBlock> FIRE_TRAP_PLATE =
            BLOCKS.register("fire_trap_plate", () -> new FireTrapPlateBlock(
                    BlockBehaviour.Properties.ofFullCopy(Blocks.MUD_BRICKS)
                            .lightLevel(state -> state.getValue(FireTrapPlateBlock.LIT) ? 10 : 0)));

    private static BlockBehaviour.Properties trapProps() {
        return BlockBehaviour.Properties.ofFullCopy(Blocks.MUD_BRICKS);
    }

    public static final DeferredBlock<HotpotBlock> HOTPOT =
            BLOCKS.register("hotpot", () -> new HotpotBlock(
                    BlockBehaviour.Properties.of()
                            .strength(0.5F)
                            .sound(SoundType.METAL)
                            .noOcclusion()
                            .pushReaction(PushReaction.DESTROY),
                    () -> ModItems.HOTPOT_SERVING.get()));
}
