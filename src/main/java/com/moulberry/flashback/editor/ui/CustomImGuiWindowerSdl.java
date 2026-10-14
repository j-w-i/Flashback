package com.moulberry.flashback.editor.ui;

/**
 * Compatibility class name retained for ReplayUI. Input is now routed through GLFW
 * callbacks installed by {@link CustomImGuiImplGlfw}; no SDL event polling is used.
 */
public final class CustomImGuiWindowerSdl extends CustomImGuiImplGlfw {
    /**
     * Compatibility no-op for the old SDL event mixin. GLFW callbacks already deliver
     * these inputs; the SDL mixin should be removed by the caller when porting the mixins.
     */
    @Deprecated
    public boolean processEvent(Object ignoredSdlEvent) {
        return false;
    }
}
