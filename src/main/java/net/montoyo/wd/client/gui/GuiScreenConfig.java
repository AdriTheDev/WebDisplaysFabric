package net.montoyo.wd.client.gui;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.network.ScreenActionPayload;
import net.montoyo.wd.utilities.data.BlockSide;
import net.montoyo.wd.utilities.data.Rotation;
import net.montoyo.wd.utilities.math.Vector2i;

public class GuiScreenConfig extends Screen {
    private final BlockPos blockPos;
    private final BlockSide side;
    private final boolean isNew;
    private ScreenData screen;
    private EditBox widthInput;
    private EditBox heightInput;

    public GuiScreenConfig(BlockPos pos, BlockSide side, boolean isNew) {
        super(Component.literal("Screen Settings"));
        this.blockPos = pos;
        this.side = side;
        this.isNew = isNew;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        net.minecraft.world.level.block.entity.BlockEntity be =
                Minecraft.getInstance().level.getBlockEntity(blockPos);
        if (be instanceof ScreenBlockEntity sbe) {
            screen = sbe.getScreen(side);
        }

        if (!isNew && screen == null) {
            this.onClose();
            return;
        }

        widthInput = new EditBox(this.font, cx - 50, cy - 35, 100, 20, Component.literal("Width"));
        widthInput.setMaxLength(3);
        widthInput.setValue(screen != null ? String.valueOf(screen.size.x) : "2");
        addRenderableWidget(widthInput);

        heightInput = new EditBox(this.font, cx - 50, cy - 5, 100, 20, Component.literal("Height"));
        heightInput.setMaxLength(3);
        heightInput.setValue(screen != null ? String.valueOf(screen.size.y) : "2");
        addRenderableWidget(heightInput);

        if (isNew) {
            addRenderableWidget(Button.builder(
                    Component.literal("Create"),
                    b -> confirmSize()
            ).bounds(cx - 50, cy + 30, 100, 20).build());
        } else {
            addRenderableWidget(Button.builder(
                    Component.literal("Apply Size"),
                    b -> confirmSize()
            ).bounds(cx - 100, cy + 30, 100, 20).build());

            addRenderableWidget(Button.builder(
                    Component.translatable("webdisplays.gui.screencfg.seturl"),
                    b -> Minecraft.getInstance().setScreen(new GuiSetURL(blockPos, side))
            ).bounds(cx, cy + 30, 100, 20).build());

            addRenderableWidget(Button.builder(
                    Component.translatable("webdisplays.gui.screencfg.rot0"),
                    b -> setRotation(Rotation.ROT_0)
            ).bounds(cx - 75, cy + 60, 45, 20).build());
            addRenderableWidget(Button.builder(
                    Component.translatable("webdisplays.gui.screencfg.rot90"),
                    b -> setRotation(Rotation.ROT_90)
            ).bounds(cx - 25, cy + 60, 45, 20).build());
            addRenderableWidget(Button.builder(
                    Component.translatable("webdisplays.gui.screencfg.rot180"),
                    b -> setRotation(Rotation.ROT_180)
            ).bounds(cx + 25, cy + 60, 45, 20).build());
            addRenderableWidget(Button.builder(
                    Component.translatable("webdisplays.gui.screencfg.rot270"),
                    b -> setRotation(Rotation.ROT_270)
            ).bounds(cx + 75, cy + 60, 45, 20).build());
        }

        addRenderableWidget(Button.builder(
                Component.translatable("webdisplays.gui.seturl.cancel"),
                b -> this.onClose()
        ).bounds(cx - 50, cy + 95, 100, 20).build());
    }

    private int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    private void confirmSize() {
        int bw = Math.max(1, Math.min(100, parseInt(widthInput.getValue(), 2)));
        int bh = Math.max(1, Math.min(100, parseInt(heightInput.getValue(), 2)));
        Vector2i size = new Vector2i(bw, bh);
        Vector2i res = new Vector2i(bw * 320, bh * 320);
        ScreenBlockEntity sbe = getBlockEntity();
        if (sbe == null) return;

        if (isNew) {
            String owner = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.getName().getString() : "unknown";
            sbe.addScreen(side, res, size, owner);
            if (ClientPlayNetworking.canSend(ScreenActionPayload.TYPE)) {
                ClientPlayNetworking.send(new ScreenActionPayload(blockPos, side.id,
                        ScreenActionPayload.ACTION_ADD_SCREEN, bw + "," + bh));
            }
        } else {
            sbe.setResolution(side, res);
            if (ClientPlayNetworking.canSend(ScreenActionPayload.TYPE)) {
                ClientPlayNetworking.send(ScreenActionPayload.setResolution(blockPos, side.id, res.x, res.y));
            }
        }
        this.onClose();
    }

    private void setRotation(Rotation rot) {
        ScreenBlockEntity sbe = getBlockEntity();
        if (sbe != null) {
            sbe.setRotation(side, rot);
            if (ClientPlayNetworking.canSend(ScreenActionPayload.TYPE)) {
                ClientPlayNetworking.send(ScreenActionPayload.setRotation(blockPos, side.id, rot.id));
            }
        }
        this.onClose();
    }

    private ScreenBlockEntity getBlockEntity() {
        net.minecraft.world.level.block.entity.BlockEntity be =
                Minecraft.getInstance().level.getBlockEntity(blockPos);
        return (be instanceof ScreenBlockEntity sbe) ? sbe : null;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        int cx = this.width / 2;

        graphics.centeredText(this.font, "Screen Size (1-100 blocks)", cx, this.height / 2 - 55, 0xFFFFFFFF);
        graphics.text(this.font, "Width:", cx - 50, this.height / 2 - 47, 0xFFCCCCCC);
        graphics.text(this.font, "Height:", cx - 50, this.height / 2 - 17, 0xFFCCCCCC);

        if (!isNew && screen != null) {
            graphics.centeredText(this.font,
                    "Owner: " + (screen.owner != null ? screen.owner : "N/A"),
                    cx, this.height / 2 - 80, 0xFFAAAAAA);
            graphics.centeredText(this.font,
                    "Resolution: " + screen.resolution.x + "x" + screen.resolution.y,
                    cx, this.height / 2 - 68, 0xFF888888);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
