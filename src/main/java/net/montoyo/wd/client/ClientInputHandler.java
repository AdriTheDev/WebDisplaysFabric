package net.montoyo.wd.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.montoyo.wd.client.mcef.MCEFHelper;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.network.ScreenInputPayload;
import net.montoyo.wd.utilities.data.BlockSide;

/**
 * Applies screen input relayed by the server to this client's browser.
 *
 * <p>Every client, including whoever performed the action, drives its browser from here rather than
 * from its own input handlers. Going through the server costs a round trip but means all copies of
 * a page receive identical events in identical order, which is what makes a screen look like one
 * shared session instead of several private ones.
 */
public final class ClientInputHandler {

    private ClientInputHandler() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(ScreenInputPayload.TYPE,
                (payload, context) -> context.client().execute(() -> apply(payload)));
    }

    private static void apply(ScreenInputPayload payload) {
        Object browser = browserAt(payload.pos(), payload.side());
        if (browser == null) return;

        switch (payload.kind()) {
            case ScreenInputPayload.KIND_MOUSE_DOWN -> {
                // Hovering is local, so other clients are not pointing here. Move first so the
                // press lands where the sender actually clicked.
                MCEFHelper.sendMouseMove(browser, payload.a(), payload.b(), false);
                MCEFHelper.sendMouseClick(browser, payload.a(), payload.b(),
                        payload.button(), false, payload.clickCount());
            }
            case ScreenInputPayload.KIND_MOUSE_UP -> MCEFHelper.sendMouseClick(browser,
                    payload.a(), payload.b(), payload.button(), true, 1);
            case ScreenInputPayload.KIND_SCROLL -> {
                MCEFHelper.sendMouseMove(browser, payload.a(), payload.b(), false);
                MCEFHelper.sendMouseWheel(browser, payload.a(), payload.b(), payload.scrollAmount(), 0);
            }
            case ScreenInputPayload.KIND_KEY_DOWN ->
                    MCEFHelper.sendKeyPress(browser, payload.a(), 0, payload.b());
            case ScreenInputPayload.KIND_KEY_UP ->
                    MCEFHelper.sendKeyRelease(browser, payload.a(), 0, payload.b());
            case ScreenInputPayload.KIND_CHAR ->
                    MCEFHelper.sendKeyEvent(browser, (char) payload.a());
            default -> { }
        }
    }

    private static Object browserAt(BlockPos pos, int sideId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        if (!(mc.level.getBlockEntity(pos) instanceof ScreenBlockEntity screen)) return null;
        ScreenData data = screen.getScreen(BlockSide.fromInt(sideId));
        return data != null ? data.browser : null;
    }

    /** Sends one interaction to the server; it comes back through {@link #apply} to be applied. */
    public static void send(ScreenInputPayload payload) {
        if (ClientPlayNetworking.canSend(ScreenInputPayload.TYPE)) {
            ClientPlayNetworking.send(payload);
        }
    }
}
