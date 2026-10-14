package com.moulberry.flashback.utils;

import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

public class WindowSizeTracker {

    /**
     * Some mods (e.g. qdaa) like to manipulate the framebuffer width
     * This can cause rendering issues since we don't know the real width
     * This helper will cache and calculate the real framebuffer width to avoid this issue
     */

    public static int getWidth(Window window) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.callocInt(1);
            IntBuffer height = stack.callocInt(1);
            GLFW.glfwGetFramebufferSize(window.getWindow(), width, height);
            return Math.max(1, width.get(0));
        }
    }

    public static int getHeight(Window window) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.callocInt(1);
            IntBuffer height = stack.callocInt(1);
            GLFW.glfwGetFramebufferSize(window.getWindow(), width, height);
            return Math.max(1, height.get(0));
        }
    }

    public static void getFramebufferSizeRaw(long handle, int[] width, int[] height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer widthBuf = stack.callocInt(1);
            IntBuffer heightBuf = stack.callocInt(1);
            GLFW.glfwGetFramebufferSize(handle, widthBuf, heightBuf);
            width[0] = widthBuf.get(0);
            height[0] = heightBuf.get(0);
        }
    }

    public static void getWindowSizeRaw(long handle, int[] width, int[] height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer widthBuf = stack.callocInt(1);
            IntBuffer heightBuf = stack.callocInt(1);
            GLFW.glfwGetWindowSize(handle, widthBuf, heightBuf);
            width[0] = widthBuf.get(0);
            height[0] = heightBuf.get(0);
        }
    }

}
