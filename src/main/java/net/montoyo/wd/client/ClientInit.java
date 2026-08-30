package net.montoyo.wd.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.montoyo.wd.client.gui.GuiScreenConfig;
import net.montoyo.wd.client.gui.InputScreen;
import net.montoyo.wd.client.mcef.MCEFHelper;
import net.montoyo.wd.entity.KeyboardBlockEntity;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.network.ScreenActionPayload;
import net.montoyo.wd.registry.WDRegistries;
import net.montoyo.wd.utilities.Log;
import net.montoyo.wd.utilities.data.BlockSide;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;

public class ClientInit implements ClientModInitializer {

    private static int previousHotbarSlot = -1;
    private static boolean wasTabDown = false;
    private static long lastUrlCheckTime = 0;
    private static final long URL_CHECK_INTERVAL_MS = 1000;
    private static boolean mcefRenderingEnabled = true;
    private static boolean wasF6Down = false;

    /**
     * Tells the server where a screen's page has actually got to, so the other clients follow.
     *
     * <p>Relaying clicks and keystrokes is not enough to keep pages together. Every client runs
     * its own Chromium with its own cookies, logins, consent prompts and load timing, so the
     * same click can land on a different page state on each of them and produce nothing. What
     * does travel reliably is the address: whichever client's page moved, everyone else is told
     * to go there, and a search or a followed link ends up on all the screens.
     *
     * <p>Only reported on an actual change of address, and only when it differs from what the
     * server already believes, so a client told to load a page does not immediately report it
     * straight back. Restricted to ordinary web addresses to stop a client that failed to load
     * something from dragging everyone onto its own error page.
     */
    private static void reportNavigation(ScreenBlockEntity screen, ScreenData data, String currentUrl) {
        if (currentUrl.equals(data.url)) return;
        if (!currentUrl.startsWith("http://") && !currentUrl.startsWith("https://")) return;
        if (!ClientPlayNetworking.canSend(ScreenActionPayload.TYPE)) return;
        ClientPlayNetworking.send(new ScreenActionPayload(screen.getBlockPos(), data.side.id,
                ScreenActionPayload.ACTION_SET_URL, currentUrl));
    }

    public static boolean isMCEFRenderingEnabled() {
        return mcefRenderingEnabled;
    }

    @Override
    public void onInitializeClient() {
        Log.info("WebDisplays client initializing...");

        // Register block entity renderers
        net.minecraft.client.renderer.blockentity.BlockEntityRenderers.register(
                WDRegistries.SCREEN_BLOCK_ENTITY, ScreenRenderer::new);

        ClientInputHandler.register();

        // Schedule MCEF initialization callback (uses reflection)
        MCEFHelper.scheduleInit(success -> {
            if (success) {
                Log.info("MCEF initialized successfully for WebDisplays");
            } else {
                Log.info("MCEF not available for WebDisplays");
            }
        });

        // Periodically retry browser creation for screens that were created before MCEF initialized
        // Also detect page navigation and re-inject window.open override (throttled to 1/sec)
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.level == null) return;
            long now = System.currentTimeMillis();
            boolean shouldCheckUrl = (now - lastUrlCheckTime) >= URL_CHECK_INTERVAL_MS;
            if (shouldCheckUrl) lastUrlCheckTime = now;

            for (ScreenBlockEntity screen : ScreenBlockEntity.getClientScreens()) {
                if (screen.retryCreateBrowsers()) {
                    Log.info("Browser retry succeeded for screen at {}", screen.getBlockPos());
                }
                // Detect page navigation and re-inject window.open override (throttled)
                if (shouldCheckUrl) {
                    for (int i = 0; i < screen.screenCount(); i++) {
                        ScreenData data = screen.getScreen(i);
                        if (data == null || data.browser == null) continue;
                        String currentUrl = MCEFHelper.getBrowserUrl(data.browser);
                        if (!currentUrl.isEmpty() && !currentUrl.equals(data.lastUrl)) {
                            data.lastUrl = currentUrl;
                            ScreenBlockEntity.ensureWindowOpenOverride(data.browser);
                            // A new page starts at full volume with none of our scripts, so
                            // restore the screen's setting alongside the window.open override.
                            MCEFHelper.setBrowserVolume(data.browser, data.volume);
                            reportNavigation(screen, data, currentUrl);
                        }
                    }
                }
            }
        });

        // Track cursor position on screen planes via raycasting
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) return;
            ScreenCursorTracker.update(client);
        });

        // Detect left-click on screen surfaces
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) return;
            ScreenCursorTracker.handleLeftClick(client);
        });

        // Handle Shift+scroll for browser scrolling, Ctrl+scroll for zoom
        // Only intercept when Shift is held AND the hotbar slot actually changed
        // due to scroll wheel (not just pressing Shift alone)
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) return;
            if (!ScreenCursorTracker.isScreenFocused()) {
                previousHotbarSlot = -1;
                return;
            }

            boolean isShift = client.player.isShiftKeyDown();
            boolean isCtrl = client.hasControlDown();

            int currentSlot = client.player.getInventory().getSelectedSlot();

            if (previousHotbarSlot < 0) {
                // Initialize tracking - just record current slot, don't trigger
                previousHotbarSlot = currentSlot;
                return;
            }

            if (currentSlot != previousHotbarSlot) {
                // Slot changed - only convert to scroll if Shift or Ctrl was already held
                // This prevents false triggers when just pressing Shift
                if (isShift || isCtrl) {
                    int delta = currentSlot - previousHotbarSlot;
                    if (delta > 4) delta -= 9;
                    else if (delta < -4) delta += 9;
                    // Only handle single-step scroll wheel changes (±1)
                    // Ignore multi-step changes (likely number key presses)
                    if (Math.abs(delta) == 1) {
                        client.player.getInventory().setSelectedSlot(previousHotbarSlot);
                        ScreenCursorTracker.handleScroll(delta > 0 ? 1.0 : -1.0);
                        return;
                    }
                }
                // For non-scroll changes or non-shift states, just update tracking
                previousHotbarSlot = currentSlot;
            }
        });

        // Toggle cursor visibility with Tab key
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) return;
            boolean isTabDown = com.mojang.blaze3d.platform.InputConstants.isKeyDown(
                    client.getWindow(), com.mojang.blaze3d.platform.InputConstants.KEY_TAB);
            if (isTabDown && !wasTabDown) {
                ScreenCursorTracker.toggleCursorVisible();
            }
            wasTabDown = isTabDown;
        });

        // Toggle MCEF screen rendering with F6 key
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) return;
            boolean isF6Down = com.mojang.blaze3d.platform.InputConstants.isKeyDown(
                    client.getWindow(), com.mojang.blaze3d.platform.InputConstants.KEY_F6);
            if (isF6Down && !wasF6Down) {
                mcefRenderingEnabled = !mcefRenderingEnabled;
                client.player.sendOverlayMessage(
                        net.minecraft.network.chat.Component.literal(
                                "WebDisplays 渲染: " + (mcefRenderingEnabled ? "开启" : "关闭")));
            }
            wasF6Down = isF6Down;
        });

        // Cancel block breaking ONLY when the actual targeted block IS the screen block
        // If a closer opaque block exists, Minecraft's pick will target that block instead
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (!ScreenCursorTracker.isCursorVisible()) return InteractionResult.PASS;
            if (!ScreenCursorTracker.isScreenFocused()) return InteractionResult.PASS;

            // Get Minecraft's actual targeted block (respects occlusion)
            Minecraft mc = Minecraft.getInstance();
            if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult blockHit) {
                // Only cancel if the targeted block position matches the screen's block position
                ScreenCursorTracker.CursorInfo cursor = ScreenCursorTracker.getCurrentCursor();
                if (cursor != null && blockHit.getBlockPos().equals(cursor.pos)) {
                    return InteractionResult.FAIL;
                }
            }
            return InteractionResult.PASS;
        });

        // Open config GUI when using configurator on screen (client-side only)
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide()) return InteractionResult.PASS;
            BlockEntity be = world.getBlockEntity(hitResult.getBlockPos());
            if (be instanceof ScreenBlockEntity screen) {
                if (player.getItemInHand(hand).getItem() == WDRegistries.CONFIGURATOR) {
                    BlockPos pos = hitResult.getBlockPos();
                    BlockSide side = BlockSide.fromDirection(hitResult.getDirection());
                    Minecraft.getInstance().setScreen(
                            new GuiScreenConfig(pos, side, !screen.hasScreen(side)));
                    return InteractionResult.SUCCESS;
                }
            }
            return InteractionResult.PASS;
        });

        // Open keyboard InputScreen when right-clicking keyboard blocks (empty hand only)
        // If holding Linker, let KeyboardBlockLeft.useItemOn handle the linking first
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide()) return InteractionResult.PASS;
            BlockEntity be = world.getBlockEntity(hitResult.getBlockPos());
            if (be instanceof KeyboardBlockEntity kb) {
                if (player.getItemInHand(hand).getItem() == WDRegistries.LINKER) return InteractionResult.PASS;
                BlockPos screenPos = kb.getLinkedPos();
                BlockSide screenSide = kb.getLinkedSide();
                if (screenPos != null && screenSide != null) {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.screen instanceof InputScreen && ((InputScreen) mc.screen).isFor(screenPos, screenSide)) {
                        mc.setScreen(null);
                        player.sendOverlayMessage(net.minecraft.network.chat.Component.literal("Input mode: OFF"));
                    } else {
                        mc.setScreen(new InputScreen(screenPos, screenSide));
                        player.sendOverlayMessage(net.minecraft.network.chat.Component.literal("Input mode: ON (ESC to exit)"));
                    }
                    return InteractionResult.SUCCESS;
                } else {
                    player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("webdisplays.message.notLinked"));
                    return InteractionResult.SUCCESS;
                }
            }
            return InteractionResult.PASS;
        });

        Log.info("WebDisplays client initialized!");
    }
}