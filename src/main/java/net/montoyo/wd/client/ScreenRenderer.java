package net.montoyo.wd.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.utilities.data.BlockSide;
import net.montoyo.wd.utilities.data.Rotation;
import org.jetbrains.annotations.Nullable;

public class ScreenRenderer implements BlockEntityRenderer<ScreenBlockEntity, ScreenRenderState> {

    /** Screens are self-lit, so they are drawn at full brightness rather than by world light. */
    private static final int FULL_BRIGHT = 0xF000F0;
    /** Pushes the quad just off the block face so it does not z-fight with the block itself. */
    private static final float SURFACE_OFFSET = 0.001f;

    public ScreenRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public ScreenRenderState createRenderState() {
        return new ScreenRenderState();
    }

    @Override
    public void extractRenderState(ScreenBlockEntity blockEntity, ScreenRenderState state, float partialTick,
                                   Vec3 cameraPos, @Nullable ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        BlockEntityRenderer.super.extractRenderState(blockEntity, state, partialTick, cameraPos, crumblingOverlay);

        state.faces.clear();
        state.hasCursor = false;

        // Vanilla hands us the live block entity straight out of the chunk, every frame the
        // block is in view, so this is the one place guaranteed to see every screen a client
        // actually has. Registering here rather than relying on setLevel alone is what stops a
        // player from ending up with a screen they can see but cannot point at.
        ScreenBlockEntity.rememberClientScreen(blockEntity);

        if (!MCEFHelperAvailable() || !ClientInit.isMCEFRenderingEnabled()) {
            return;
        }

        BlockPos pos = blockEntity.getBlockPos();

        for (int i = 0; i < blockEntity.screenCount(); i++) {
            ScreenData screen = blockEntity.getScreen(i);
            if (screen == null || screen.browser == null) continue;
            if (isBlockedByOpaque(pos, screen)) continue;

            Identifier texture = BrowserTextureBridge.textureIdFor(screen.browser, BrowserTextureBridge.keyFor(pos, screen.side));
            if (texture == null) continue;

            ScreenRenderState.Face face = new ScreenRenderState.Face();
            face.side = screen.side;
            face.rotation = screen.rotation;
            face.texture = texture;
            face.width = screen.size.x;
            face.height = screen.size.y;
            face.resolutionX = screen.resolution.x;
            face.resolutionY = screen.resolution.y;
            state.faces.add(face);
        }

        ScreenCursorTracker.CursorInfo cursor = ScreenCursorTracker.getCurrentCursor();
        if (ScreenCursorTracker.isCursorVisible() && cursor != null && cursor.pos.equals(pos)) {
            state.hasCursor = true;
            state.cursorSide = cursor.side;
            state.cursorX = (float) cursor.localX;
            state.cursorY = (float) cursor.localY;
            state.cursorZ = (float) cursor.localZ;
        }
    }

    @Override
    public void submit(ScreenRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                       CameraRenderState cameraState) {
        for (ScreenRenderState.Face face : state.faces) {
            submitFace(face, poseStack, collector);
        }

        if (state.hasCursor) {
            submitCursor(state, poseStack, collector);
        }
    }

    private void submitFace(ScreenRenderState.Face face, PoseStack poseStack, SubmitNodeCollector collector) {
        float[] uv = computeUvs(face);

        poseStack.pushPose();
        translateToSurface(poseStack, face.side);
        // North and east faces are drawn along +x / +z, which is the viewer's left for those
        // two. Shifting the quad back by its width makes every face grow to the viewer's
        // right instead, so placement behaves the same whichever way you are looking.
        switch (face.side) {
            case NORTH -> poseStack.translate(1.0f - face.width, 0.0f, 0.0f);
            case EAST -> poseStack.translate(0.0f, 0.0f, 1.0f - face.width);
            default -> { }
        }

        collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(face.texture),
                (pose, consumer) -> writeQuad(pose, consumer, face, uv));

        poseStack.popPose();
    }

    /**
     * Computes UVs that "cover" the screen surface: the browser image is cropped rather than
     * stretched when its aspect ratio differs from the screen's, then rotated to taste.
     */
    private static float[] computeUvs(ScreenRenderState.Face face) {
        float screenAspect = face.width / face.height;
        float browserAspect = (float) face.resolutionX / (float) face.resolutionY;

        float u0 = 0f, v0 = 0f, u1 = 1f, v1 = 1f;

        if (screenAspect > browserAspect) {
            float ratio = screenAspect / browserAspect;
            float offset = (1.0f - 1.0f / ratio) / 2.0f;
            u0 = offset;
            u1 = 1.0f - offset;
        } else if (screenAspect < browserAspect) {
            float ratio = browserAspect / screenAspect;
            float offset = (1.0f - 1.0f / ratio) / 2.0f;
            v0 = offset;
            v1 = 1.0f - offset;
        }

        return switch (face.rotation) {
            case ROT_90 -> new float[]{u1, v0, u0, v1};
            case ROT_180 -> new float[]{u1, v1, u0, v0};
            case ROT_270 -> new float[]{u0, v1, u1, v0};
            default -> new float[]{u0, v0, u1, v1};
        };
    }

    private static void translateToSurface(PoseStack poseStack, BlockSide side) {
        switch (side) {
            case BOTTOM -> poseStack.translate(0, -SURFACE_OFFSET, 0);
            case TOP -> poseStack.translate(0, 1.0 + SURFACE_OFFSET, 0);
            case NORTH -> poseStack.translate(0, 0, -SURFACE_OFFSET);
            case SOUTH -> poseStack.translate(0, 0, 1.0 + SURFACE_OFFSET);
            case WEST -> poseStack.translate(-SURFACE_OFFSET, 0, 0);
            case EAST -> poseStack.translate(1.0 + SURFACE_OFFSET, 0, 0);
        }
    }

    private static void writeQuad(PoseStack.Pose pose, VertexConsumer consumer,
                                  ScreenRenderState.Face face, float[] uv) {
        float u0 = uv[0], v0 = uv[1], u1 = uv[2], v1 = uv[3];
        float w = face.width;
        float h = face.height;

        Direction normal = face.side.direction;

        switch (face.side) {
            case NORTH -> {
                vertex(pose, consumer, 0, 0, 0, u1, v1, normal);
                vertex(pose, consumer, 0, h, 0, u1, v0, normal);
                vertex(pose, consumer, w, h, 0, u0, v0, normal);
                vertex(pose, consumer, w, 0, 0, u0, v1, normal);
            }
            case SOUTH -> {
                vertex(pose, consumer, w, 0, 0, u1, v1, normal);
                vertex(pose, consumer, w, h, 0, u1, v0, normal);
                vertex(pose, consumer, 0, h, 0, u0, v0, normal);
                vertex(pose, consumer, 0, 0, 0, u0, v1, normal);
            }
            case WEST -> {
                vertex(pose, consumer, 0, 0, 0, u0, v1, normal);
                vertex(pose, consumer, 0, 0, w, u1, v1, normal);
                vertex(pose, consumer, 0, h, w, u1, v0, normal);
                vertex(pose, consumer, 0, h, 0, u0, v0, normal);
            }
            case EAST -> {
                vertex(pose, consumer, 0, 0, w, u0, v1, normal);
                vertex(pose, consumer, 0, 0, 0, u1, v1, normal);
                vertex(pose, consumer, 0, h, 0, u1, v0, normal);
                vertex(pose, consumer, 0, h, w, u0, v0, normal);
            }
            case BOTTOM, TOP -> {
                vertex(pose, consumer, 0, 0, 0, u0, v1, normal);
                vertex(pose, consumer, w, 0, 0, u1, v1, normal);
                vertex(pose, consumer, w, 0, h, u1, v0, normal);
                vertex(pose, consumer, 0, 0, h, u0, v0, normal);
            }
        }
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer consumer,
                               float x, float y, float z, float u, float v, Direction normal) {
        consumer.addVertex(pose, x, y, z)
                .setColor(0xFFFFFFFF)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(FULL_BRIGHT)
                .setNormal(pose, normal.getStepX(), normal.getStepY(), normal.getStepZ());
    }

    private void submitCursor(ScreenRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
        BlockSide side = state.cursorSide;
        float size = 0.04f;

        float lx = state.cursorX;
        float ly = state.cursorY;
        float lz = state.cursorZ;

        switch (side) {
            case SOUTH -> lz -= 1.0f;
            case EAST -> lx -= 1.0f;
            case TOP -> ly -= 1.0f;
        }

        final float cx = lx, cy = ly, cz = lz;

        float rx = (float) side.right.x * size;
        float ry = (float) side.right.y * size;
        float rz = (float) side.right.z * size;
        float ux = (float) side.up.x * size;
        float uy = (float) side.up.y * size;
        float uz = (float) side.up.z * size;

        poseStack.pushPose();
        translateToSurface(poseStack, side);

        collector.submitCustomGeometry(poseStack, RenderTypes.debugQuads(), (pose, consumer) -> {
            cursorVertex(pose, consumer, cx - rx - ux, cy - ry - uy, cz - rz - uz);
            cursorVertex(pose, consumer, cx - rx + ux, cy - ry + uy, cz - rz + uz);
            cursorVertex(pose, consumer, cx + rx + ux, cy + ry + uy, cz + rz + uz);
            cursorVertex(pose, consumer, cx + rx - ux, cy + ry - uy, cz + rz - uz);
        });

        poseStack.popPose();
    }

    private static void cursorVertex(PoseStack.Pose pose, VertexConsumer consumer, float x, float y, float z) {
        consumer.addVertex(pose, x, y, z).setColor(255, 51, 51, 230);
    }

    private static boolean isBlockedByOpaque(BlockPos pos, ScreenData screen) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        BlockState state = mc.level.getBlockState(pos.relative(screen.side.direction));
        return state.isSolid();
    }

    private static boolean MCEFHelperAvailable() {
        return net.montoyo.wd.client.mcef.MCEFHelper.isMCEFAvailable();
    }

    @Override
    public int getViewDistance() {
        return 32;
    }
}
