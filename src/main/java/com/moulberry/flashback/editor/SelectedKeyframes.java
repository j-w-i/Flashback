package com.moulberry.flashback.editor;

import com.moulberry.flashback.keyframe.KeyframeType;
import com.moulberry.flashback.state.EditorScene;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.KeyframeTrack;
import it.unimi.dsi.fastutil.ints.IntSet;

public record SelectedKeyframes(KeyframeType<?> type, int trackIndex, IntSet keyframeTicks) {
    public boolean checkValid(EditorState state) {
        if (state == null || this.keyframeTicks == null || this.keyframeTicks.isEmpty()) {
            return false;
        }
        EditorScene scene = state.currentSceneOrNull();
        if (scene == null || scene.keyframeTracks == null ||
                this.trackIndex < 0 || this.trackIndex >= scene.keyframeTracks.size()) {
            return false;
        }

        KeyframeTrack keyframeTrack = scene.keyframeTracks.get(this.trackIndex);
        if (keyframeTrack == null) {
            return false;
        }
        if (keyframeTrack.keyframesByTick == null || keyframeTrack.keyframeType != this.type) {
            return false;
        }

        this.keyframeTicks.removeIf(tick -> !keyframeTrack.keyframesByTick.containsKey(tick));
        return !this.keyframeTicks.isEmpty();
    }
}
