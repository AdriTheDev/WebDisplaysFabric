package net.montoyo.wd.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * One interaction with a screen: a click, a scroll, or a key press.
 *
 * <p>Travels in both directions. A client sends its own input to the server, which relays it to
 * everyone watching that block — the sender included. Clients apply input only when it comes back,
 * so every browser in the world receives the same events in the same order and the screens stay in
 * step. The alternative, applying locally and telling everyone else afterwards, lets the person
 * interacting drift away from what everyone else sees.
 *
 * <p>Mouse movement is deliberately absent: hovering is private to each player, so it stays local.
 * A click therefore carries its own coordinates and the receiver moves the cursor there first,
 * rather than assuming every client is already hovering the same spot.
 */
public record ScreenInputPayload(BlockPos pos, int side, int kind, int a, int b, int c)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ScreenInputPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("webdisplays", "screen_input"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenInputPayload> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, ScreenInputPayload::pos,
                    ByteBufCodecs.VAR_INT, ScreenInputPayload::side,
                    ByteBufCodecs.VAR_INT, ScreenInputPayload::kind,
                    ByteBufCodecs.VAR_INT, ScreenInputPayload::a,
                    ByteBufCodecs.VAR_INT, ScreenInputPayload::b,
                    ByteBufCodecs.VAR_INT, ScreenInputPayload::c,
                    ScreenInputPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static final int KIND_MOUSE_DOWN = 0;
    public static final int KIND_MOUSE_UP = 1;
    public static final int KIND_SCROLL = 2;
    public static final int KIND_KEY_DOWN = 3;
    public static final int KIND_KEY_UP = 4;
    public static final int KIND_CHAR = 5;

    /** Scroll amounts are fractional; this is the fixed-point scale used to send them as ints. */
    public static final double SCROLL_SCALE = 1000.0;

    public static ScreenInputPayload mouseDown(BlockPos pos, int side, int x, int y, int button, int clickCount) {
        return new ScreenInputPayload(pos, side, KIND_MOUSE_DOWN, x, y, button | (clickCount << 8));
    }

    public static ScreenInputPayload mouseUp(BlockPos pos, int side, int x, int y, int button) {
        return new ScreenInputPayload(pos, side, KIND_MOUSE_UP, x, y, button);
    }

    public static ScreenInputPayload scroll(BlockPos pos, int side, int x, int y, double amount) {
        return new ScreenInputPayload(pos, side, KIND_SCROLL, x, y, (int) Math.round(amount * SCROLL_SCALE));
    }

    public static ScreenInputPayload keyDown(BlockPos pos, int side, int key, int modifiers) {
        return new ScreenInputPayload(pos, side, KIND_KEY_DOWN, key, modifiers, 0);
    }

    public static ScreenInputPayload keyUp(BlockPos pos, int side, int key, int modifiers) {
        return new ScreenInputPayload(pos, side, KIND_KEY_UP, key, modifiers, 0);
    }

    public static ScreenInputPayload charTyped(BlockPos pos, int side, int codePoint) {
        return new ScreenInputPayload(pos, side, KIND_CHAR, codePoint, 0, 0);
    }

    public int button() {
        return c & 0xFF;
    }

    public int clickCount() {
        return Math.max(1, (c >> 8) & 0xFF);
    }

    public double scrollAmount() {
        return c / SCROLL_SCALE;
    }
}
