package com.moulberry.flashback.visuals;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/** Legacy 1.21.1 implementation of the replay entity debug outlines. */
public class FlashbackDebugRenderer implements DebugRenderer.SimpleDebugRenderer {
    private final Minecraft minecraft;

    public FlashbackDebugRenderer(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffers, double cameraX, double cameraY, double cameraZ) {
        if (!Flashback.isInReplay() || this.minecraft.level == null || Flashback.isExporting() || !ReplayUI.isActive()) {
            return;
        }

        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState == null || !editorState.maybeHasHiddenEntities()) {
            return;
        }

        float partialTick = this.minecraft.timer.getGameTimeDeltaPartialTick(true);
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (Entity entity : this.minecraft.level.entitiesForRendering()) {
            if (!editorState.isEntityHidden(entity) || entity == this.minecraft.player) {
                continue;
            }

            AABB box = entity.getBoundingBox().move(
                    entity.getPosition(partialTick).x - entity.getX() - cameraX,
                    entity.getPosition(partialTick).y - entity.getY() - cameraY,
                    entity.getPosition(partialTick).z - entity.getZ() - cameraZ
            );
            LevelRenderer.renderLineBox(poseStack, lines, box, 1.0F, 1.0F, 1.0F, 0.19F);
        }
    }
}
