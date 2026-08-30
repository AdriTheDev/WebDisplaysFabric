package net.montoyo.wd.network;

import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.utilities.Log;
import net.montoyo.wd.utilities.data.BlockSide;
import net.montoyo.wd.utilities.data.Rotation;
import net.montoyo.wd.utilities.math.Vector2i;

public class ServerNetHandler {

    /** How far a player may be from a screen and still drive it. */
    private static final double MAX_INTERACT_DISTANCE_SQ = 64.0 * 64.0;

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(ScreenInputPayload.TYPE, (payload, context) -> {
            ServerPlayer sender = context.player();
            context.server().execute(() -> {
                ServerLevel level = sender.level();
                if (!level.isLoaded(payload.pos())) return;
                if (sender.distanceToSqr(payload.pos().getX() + 0.5, payload.pos().getY() + 0.5,
                        payload.pos().getZ() + 0.5) > MAX_INTERACT_DISTANCE_SQ) return;
                if (!(level.getBlockEntity(payload.pos()) instanceof ScreenBlockEntity)) return;

                // Relay to everyone watching, including the sender: clients apply input only on
                // receipt, so this is what keeps every copy of the page in step.
                for (ServerPlayer viewer : PlayerLookup.tracking(level, payload.pos())) {
                    ServerPlayNetworking.send(viewer, payload);
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(ScreenActionPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            Level level = player.level();
            if (!level.isLoaded(payload.pos())) return;

            context.server().execute(() -> {
                BlockEntity be = level.getBlockEntity(payload.pos());
                if (!(be instanceof ScreenBlockEntity screen)) return;

                BlockSide side = BlockSide.fromInt(payload.sideOrdinal());
                String owner = player.getName().getString();

                switch (payload.action()) {
                    case ScreenActionPayload.ACTION_ADD_SCREEN -> {
                        if (!screen.hasScreen(side)) {
                            int bw = 2, bh = 2;
                            String[] parts = payload.extraData().split(",");
                            if (parts.length == 2) {
                                try {
                                    bw = Math.max(1, Math.min(100, Integer.parseInt(parts[0])));
                                    bh = Math.max(1, Math.min(100, Integer.parseInt(parts[1])));
                                } catch (NumberFormatException e) {}
                            }
                            Vector2i size = new Vector2i(bw, bh);
                            Vector2i res = new Vector2i(bw * 320, bh * 320);
                            screen.addScreen(side, res, size, owner);
                            screen.setChanged();
                            level.sendBlockUpdated(payload.pos(), level.getBlockState(payload.pos()),
                                    level.getBlockState(payload.pos()), 3);
                        }
                    }
                    case ScreenActionPayload.ACTION_REMOVE_SCREEN -> {
                        screen.removeScreen(side);
                    }
                    case ScreenActionPayload.ACTION_SET_URL -> {
                        screen.setScreenURL(side, payload.extraData());
                    }
                    case ScreenActionPayload.ACTION_SET_RESOLUTION -> {
                        String[] parts = payload.extraData().split(",");
                        if (parts.length == 2) {
                            try {
                                int w = Integer.parseInt(parts[0]);
                                int h = Integer.parseInt(parts[1]);
                                screen.setResolution(side, new Vector2i(w, h));
                            } catch (NumberFormatException e) {
                                Log.warning("Invalid resolution data: {}", payload.extraData());
                            }
                        }
                    }
                    case ScreenActionPayload.ACTION_SET_VOLUME -> {
                        try {
                            screen.setVolume(side, Float.parseFloat(payload.extraData()));
                        } catch (NumberFormatException e) {
                            Log.warning("Invalid volume data: {}", payload.extraData());
                        }
                    }
                    case ScreenActionPayload.ACTION_SET_ROTATION -> {
                        try {
                            int rot = Integer.parseInt(payload.extraData());
                            screen.setRotation(side, Rotation.fromInt(rot));
                        } catch (NumberFormatException e) {
                            Log.warning("Invalid rotation data: {}", payload.extraData());
                        }
                    }
                }
            });
        });
    }
}
