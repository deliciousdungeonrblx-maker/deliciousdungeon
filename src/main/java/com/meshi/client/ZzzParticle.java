package com.meshi.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;

/** A "z" that drifts up from a sleeping walking mushroom, swaying a little, growing and fading out. */
public class ZzzParticle extends TextureSheetParticle {
    private final double swayOffset;

    protected ZzzParticle(ClientLevel level, double x, double y, double z, SpriteSet sprites) {
        super(level, x, y, z, 0.0D, 0.0D, 0.0D);
        this.pickSprite(sprites);
        this.lifetime = 40 + this.random.nextInt(12);
        this.quadSize = 0.09F;
        this.gravity = 0.0F;
        this.hasPhysics = false;
        this.xd = 0.0D;
        this.yd = 0.022D;
        this.zd = 0.0D;
        this.swayOffset = this.random.nextDouble() * Math.PI * 2.0D;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        if (this.age++ >= this.lifetime) {
            this.remove();
            return;
        }
        float progress = (float) this.age / (float) this.lifetime;
        this.x += Math.sin(this.age * 0.25D + this.swayOffset) * 0.006D;
        this.y += this.yd;
        this.quadSize = 0.09F + 0.09F * progress;
        this.alpha = progress < 0.6F ? 1.0F : 1.0F - (progress - 0.6F) / 0.4F;
    }

    public static class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z,
                                       double xSpeed, double ySpeed, double zSpeed) {
            return new ZzzParticle(level, x, y, z, this.sprites);
        }
    }
}
