package net.montoyo.wd.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.montoyo.wd.utilities.data.BlockSide;
import net.montoyo.wd.client.mcef.MCEFHelper;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Publishes an MCEF browser's GPU texture to the {@code TextureManager} under a stable
 * {@link Identifier}, so it can be referenced by a {@code RenderType} for in-world rendering.
 *
 * <p>Since 1.21.5 the render pipeline addresses textures through {@code GpuTextureView} rather
 * than raw GL handles, and world geometry is drawn via render types that look their texture up by
 * identifier. This class is the adapter between the two: a lightweight {@link AbstractTexture}
 * whose backing texture is owned by MCEF, not by Minecraft.
 *
 * <p>Ownership matters here: the texture belongs to the browser, so {@link BrowserTexture#close()}
 * deliberately does nothing. Releasing it is the browser's job, and freeing it here would tear a
 * live browser's texture out from under MCEF.
 */
public final class BrowserTextureBridge {
    private static final ConcurrentHashMap<String, Identifier> REGISTERED = new ConcurrentHashMap<>();

    private BrowserTextureBridge() {
    }

    /**
     * Returns the texture identifier to render {@code browser} with, registering it on first use,
     * or null while the browser has not produced a frame yet.
     */
    public static Identifier textureIdFor(Object browser, String key) {
        if (browser == null) return null;
        GpuTextureView view = MCEFHelper.getBrowserTextureView(browser);
        if (view == null) return null;

        return REGISTERED.computeIfAbsent(key, k -> {
            Identifier id = Identifier.fromNamespaceAndPath("webdisplays", "browser/" + k);
            Minecraft.getInstance().getTextureManager().register(id, new BrowserTexture(browser));
            return id;
        });
    }

    /** Stable per-face key, shared by the renderer and the block entity's cleanup path. */
    public static String keyFor(BlockPos pos, BlockSide side) {
        return pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + "_" + side.id;
    }

    /** Drops the texture registration for a screen that is going away. */
    public static void release(String key) {
        Identifier id = REGISTERED.remove(key);
        if (id != null) {
            Minecraft.getInstance().getTextureManager().release(id);
        }
    }

    private static final class BrowserTexture extends AbstractTexture {
        private final Object browser;

        private BrowserTexture(Object browser) {
            this.browser = browser;
            // Browser frames are scaled to arbitrary screen sizes, so clamp and filter linearly.
            this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        }

        @Override
        public GpuTexture getTexture() {
            GpuTextureView view = MCEFHelper.getBrowserTextureView(browser);
            return view != null ? view.texture() : null;
        }

        @Override
        public GpuTextureView getTextureView() {
            return MCEFHelper.getBrowserTextureView(browser);
        }

        @Override
        public void close() {
            // The browser owns this texture; MCEF frees it when the browser is closed.
        }
    }
}
