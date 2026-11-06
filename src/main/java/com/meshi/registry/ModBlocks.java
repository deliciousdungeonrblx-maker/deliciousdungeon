package com.meshi.registry;

import com.meshi.Meshi;
import com.meshi.block.HotpotBlock;
import com.meshi.block.ScorpionBurrowBlock;
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

    public static final DeferredBlock<HotpotBlock> HOTPOT =
            BLOCKS.register("hotpot", () -> new HotpotBlock(
                    BlockBehaviour.Properties.of()
                            .strength(0.5F)
                            .sound(SoundType.METAL)
                            .noOcclusion()
                            .pushReaction(PushReaction.DESTROY),
                    () -> ModItems.HOTPOT_SERVING.get()));
}
