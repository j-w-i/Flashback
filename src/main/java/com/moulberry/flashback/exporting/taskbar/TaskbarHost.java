package com.moulberry.flashback.exporting.taskbar;

import com.moulberry.flashback.Flashback;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.*;
import com.sun.jna.ptr.PointerByReference;
import net.minecraft.client.Minecraft;
import net.minecraft.Util;
import org.lwjgl.glfw.GLFWNativeWin32;

public class TaskbarHost {
    public static ITaskbar createTaskbar() {
        if (Util.getPlatform() == Util.OS.WINDOWS) {
            try {
                return createWindowsInterface();
            } catch (Exception e) {
                Flashback.LOGGER.error("Unable to create windows taskbar interface", e);
                return new NoopTaskbar();
            }
        } else {
            return new NoopTaskbar();
        }
    }

    private static WindowsTaskbar createWindowsInterface() {
        var itaskbar3res = new PointerByReference();

        if (W32Errors.FAILED(Ole32.INSTANCE.CoCreateInstance(new Guid.GUID("56FDF344-FD6D-11d0-958A-006097C9A090"),
                null,
                WTypes.CLSCTX_SERVER,
                new Guid.GUID("EA1AFB91-9E28-4B86-90E9-9E9F8A5EEFAF"),
                itaskbar3res))) {
            throw new IllegalStateException("Failed to create ITaskbar3");
        }

        long windowHandle = Minecraft.getInstance().getWindow().getWindow();
        long win32Hwnd = GLFWNativeWin32.glfwGetWin32Window(windowHandle);
        if (win32Hwnd == 0) {
            throw new RuntimeException("Failed to get Win32 Window");
        }
        var hwnd = new WinDef.HWND(new Pointer(win32Hwnd));
        return new WindowsTaskbar(itaskbar3res.getValue(), hwnd);
    }
}
