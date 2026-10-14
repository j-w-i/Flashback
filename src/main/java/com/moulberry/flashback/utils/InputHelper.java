package com.moulberry.flashback.utils;

import imgui.moulberry90.flag.ImGuiKey;
import imgui.moulberry90.flag.ImGuiMouseButton;
import net.minecraft.client.Minecraft;
import net.minecraft.Util;
import static org.lwjgl.glfw.GLFW.*;

public class InputHelper {

    public static final int EDIT_SHORTCUT_KEY_LEFT = Util.getPlatform() == Util.OS.OSX ? GLFW_KEY_LEFT_SUPER : GLFW_KEY_LEFT_CONTROL;
    public static final int EDIT_SHORTCUT_KEY_RIGHT = Util.getPlatform() == Util.OS.OSX ? GLFW_KEY_RIGHT_SUPER : GLFW_KEY_RIGHT_CONTROL;

    private static long windowHandle() {
        return Minecraft.getInstance().getWindow().getWindow();
    }

    private static boolean keyDown(int key) {
        return key != GLFW_KEY_UNKNOWN && glfwGetKey(windowHandle(), key) == GLFW_PRESS;
    }

    public static boolean isCtrlOrCmdDownRaw() {
        return keyDown(EDIT_SHORTCUT_KEY_LEFT) || keyDown(EDIT_SHORTCUT_KEY_RIGHT);
    }

    public static boolean isCtrlDownRaw() {
        return keyDown(GLFW_KEY_LEFT_CONTROL) || keyDown(GLFW_KEY_RIGHT_CONTROL);
    }

    public static boolean isShiftDownRaw() {
        return keyDown(GLFW_KEY_LEFT_SHIFT) || keyDown(GLFW_KEY_RIGHT_SHIFT);
    }

    public static boolean isAltDownRaw() {
        return keyDown(GLFW_KEY_LEFT_ALT) || keyDown(GLFW_KEY_RIGHT_ALT);
    }

    public static boolean isSuperDownRaw() {
        return keyDown(GLFW_KEY_LEFT_SUPER) || keyDown(GLFW_KEY_RIGHT_SUPER);
    }

    public static boolean isKeyDownRaw(int imguiKey) {
        return keyDown(imguiKeyToGlfwKey(imguiKey));
    }

    public static boolean isMouseDownRaw(int imguiButton) {
        int button = imguiMouseToGlfwMouse(imguiButton);
        return button != -1 && glfwGetMouseButton(windowHandle(), button) == GLFW_PRESS;
    }

    public static int imguiMouseToGlfwMouse(int imgui) {
        return switch (imgui) {
            case ImGuiMouseButton.Left -> GLFW_MOUSE_BUTTON_LEFT;
            case ImGuiMouseButton.Right -> GLFW_MOUSE_BUTTON_RIGHT;
            case ImGuiMouseButton.Middle -> GLFW_MOUSE_BUTTON_MIDDLE;
            case 3 -> GLFW_MOUSE_BUTTON_4;
            case 4 -> GLFW_MOUSE_BUTTON_5;
            default -> -1;
        };
    }

    /** Compatibility alias; the returned value is a GLFW mouse-button index. */
    public static int imguiMouseToSdlMouse(int imgui) {
        return imguiMouseToGlfwMouse(imgui);
    }

    public static int glfwMouseToImguiMouse(int glfwButton) {
        return switch (glfwButton) {
            case GLFW_MOUSE_BUTTON_LEFT -> ImGuiMouseButton.Left;
            case GLFW_MOUSE_BUTTON_RIGHT -> ImGuiMouseButton.Right;
            case GLFW_MOUSE_BUTTON_MIDDLE -> ImGuiMouseButton.Middle;
            case GLFW_MOUSE_BUTTON_4 -> 3;
            case GLFW_MOUSE_BUTTON_5 -> 4;
            default -> -1;
        };
    }

    /** Compatibility alias; accepts a GLFW mouse-button index. */
    public static int sdlMouseToImguiMouse(int glfwButton) {
        return glfwMouseToImguiMouse(glfwButton);
    }

    public static int imguiKeyToGlfwKey(int imgui) {
        return switch (imgui) {
            case ImGuiKey.Tab -> GLFW_KEY_TAB;
            case ImGuiKey.LeftArrow -> GLFW_KEY_LEFT;
            case ImGuiKey.RightArrow -> GLFW_KEY_RIGHT;
            case ImGuiKey.UpArrow -> GLFW_KEY_UP;
            case ImGuiKey.DownArrow -> GLFW_KEY_DOWN;
            case ImGuiKey.PageUp -> GLFW_KEY_PAGE_UP;
            case ImGuiKey.PageDown -> GLFW_KEY_PAGE_DOWN;
            case ImGuiKey.Home -> GLFW_KEY_HOME;
            case ImGuiKey.End -> GLFW_KEY_END;
            case ImGuiKey.Insert -> GLFW_KEY_INSERT;
            case ImGuiKey.Delete -> GLFW_KEY_DELETE;
            case ImGuiKey.Backspace -> GLFW_KEY_BACKSPACE;
            case ImGuiKey.Space -> GLFW_KEY_SPACE;
            case ImGuiKey.Enter, ImGuiKey.KeypadEnter -> imgui == ImGuiKey.Enter ? GLFW_KEY_ENTER : GLFW_KEY_KP_ENTER;
            case ImGuiKey.Escape -> GLFW_KEY_ESCAPE;
            case ImGuiKey.LeftCtrl -> GLFW_KEY_LEFT_CONTROL;
            case ImGuiKey.LeftShift -> GLFW_KEY_LEFT_SHIFT;
            case ImGuiKey.LeftAlt -> GLFW_KEY_LEFT_ALT;
            case ImGuiKey.LeftSuper -> GLFW_KEY_LEFT_SUPER;
            case ImGuiKey.RightCtrl -> GLFW_KEY_RIGHT_CONTROL;
            case ImGuiKey.RightShift -> GLFW_KEY_RIGHT_SHIFT;
            case ImGuiKey.RightAlt -> GLFW_KEY_RIGHT_ALT;
            case ImGuiKey.RightSuper -> GLFW_KEY_RIGHT_SUPER;
            case ImGuiKey.Menu -> GLFW_KEY_MENU;
            case ImGuiKey._0 -> GLFW_KEY_0;
            case ImGuiKey._1 -> GLFW_KEY_1;
            case ImGuiKey._2 -> GLFW_KEY_2;
            case ImGuiKey._3 -> GLFW_KEY_3;
            case ImGuiKey._4 -> GLFW_KEY_4;
            case ImGuiKey._5 -> GLFW_KEY_5;
            case ImGuiKey._6 -> GLFW_KEY_6;
            case ImGuiKey._7 -> GLFW_KEY_7;
            case ImGuiKey._8 -> GLFW_KEY_8;
            case ImGuiKey._9 -> GLFW_KEY_9;
            case ImGuiKey.A -> GLFW_KEY_A;
            case ImGuiKey.B -> GLFW_KEY_B;
            case ImGuiKey.C -> GLFW_KEY_C;
            case ImGuiKey.D -> GLFW_KEY_D;
            case ImGuiKey.E -> GLFW_KEY_E;
            case ImGuiKey.F -> GLFW_KEY_F;
            case ImGuiKey.G -> GLFW_KEY_G;
            case ImGuiKey.H -> GLFW_KEY_H;
            case ImGuiKey.I -> GLFW_KEY_I;
            case ImGuiKey.J -> GLFW_KEY_J;
            case ImGuiKey.K -> GLFW_KEY_K;
            case ImGuiKey.L -> GLFW_KEY_L;
            case ImGuiKey.M -> GLFW_KEY_M;
            case ImGuiKey.N -> GLFW_KEY_N;
            case ImGuiKey.O -> GLFW_KEY_O;
            case ImGuiKey.P -> GLFW_KEY_P;
            case ImGuiKey.Q -> GLFW_KEY_Q;
            case ImGuiKey.R -> GLFW_KEY_R;
            case ImGuiKey.S -> GLFW_KEY_S;
            case ImGuiKey.T -> GLFW_KEY_T;
            case ImGuiKey.U -> GLFW_KEY_U;
            case ImGuiKey.V -> GLFW_KEY_V;
            case ImGuiKey.W -> GLFW_KEY_W;
            case ImGuiKey.X -> GLFW_KEY_X;
            case ImGuiKey.Y -> GLFW_KEY_Y;
            case ImGuiKey.Z -> GLFW_KEY_Z;
            case ImGuiKey.F1 -> GLFW_KEY_F1;
            case ImGuiKey.F2 -> GLFW_KEY_F2;
            case ImGuiKey.F3 -> GLFW_KEY_F3;
            case ImGuiKey.F4 -> GLFW_KEY_F4;
            case ImGuiKey.F5 -> GLFW_KEY_F5;
            case ImGuiKey.F6 -> GLFW_KEY_F6;
            case ImGuiKey.F7 -> GLFW_KEY_F7;
            case ImGuiKey.F8 -> GLFW_KEY_F8;
            case ImGuiKey.F9 -> GLFW_KEY_F9;
            case ImGuiKey.F10 -> GLFW_KEY_F10;
            case ImGuiKey.F11 -> GLFW_KEY_F11;
            case ImGuiKey.F12 -> GLFW_KEY_F12;
            case ImGuiKey.F13 -> GLFW_KEY_F13;
            case ImGuiKey.F14 -> GLFW_KEY_F14;
            case ImGuiKey.F15 -> GLFW_KEY_F15;
            case ImGuiKey.F16 -> GLFW_KEY_F16;
            case ImGuiKey.F17 -> GLFW_KEY_F17;
            case ImGuiKey.F18 -> GLFW_KEY_F18;
            case ImGuiKey.F19 -> GLFW_KEY_F19;
            case ImGuiKey.F20 -> GLFW_KEY_F20;
            case ImGuiKey.F21 -> GLFW_KEY_F21;
            case ImGuiKey.F22 -> GLFW_KEY_F22;
            case ImGuiKey.F23 -> GLFW_KEY_F23;
            case ImGuiKey.F24 -> GLFW_KEY_F24;
            case ImGuiKey.Apostrophe -> GLFW_KEY_APOSTROPHE;
            case ImGuiKey.Comma -> GLFW_KEY_COMMA;
            case ImGuiKey.Minus -> GLFW_KEY_MINUS;
            case ImGuiKey.Period -> GLFW_KEY_PERIOD;
            case ImGuiKey.Slash -> GLFW_KEY_SLASH;
            case ImGuiKey.Semicolon -> GLFW_KEY_SEMICOLON;
            case ImGuiKey.Equal -> GLFW_KEY_EQUAL;
            case ImGuiKey.LeftBracket -> GLFW_KEY_LEFT_BRACKET;
            case ImGuiKey.Backslash -> GLFW_KEY_BACKSLASH;
            case ImGuiKey.RightBracket -> GLFW_KEY_RIGHT_BRACKET;
            case ImGuiKey.GraveAccent -> GLFW_KEY_GRAVE_ACCENT;
            case ImGuiKey.CapsLock -> GLFW_KEY_CAPS_LOCK;
            case ImGuiKey.ScrollLock -> GLFW_KEY_SCROLL_LOCK;
            case ImGuiKey.NumLock -> GLFW_KEY_NUM_LOCK;
            case ImGuiKey.PrintScreen -> GLFW_KEY_PRINT_SCREEN;
            case ImGuiKey.Pause -> GLFW_KEY_PAUSE;
            case ImGuiKey.Keypad0 -> GLFW_KEY_KP_0;
            case ImGuiKey.Keypad1 -> GLFW_KEY_KP_1;
            case ImGuiKey.Keypad2 -> GLFW_KEY_KP_2;
            case ImGuiKey.Keypad3 -> GLFW_KEY_KP_3;
            case ImGuiKey.Keypad4 -> GLFW_KEY_KP_4;
            case ImGuiKey.Keypad5 -> GLFW_KEY_KP_5;
            case ImGuiKey.Keypad6 -> GLFW_KEY_KP_6;
            case ImGuiKey.Keypad7 -> GLFW_KEY_KP_7;
            case ImGuiKey.Keypad8 -> GLFW_KEY_KP_8;
            case ImGuiKey.Keypad9 -> GLFW_KEY_KP_9;
            case ImGuiKey.KeypadDecimal -> GLFW_KEY_KP_DECIMAL;
            case ImGuiKey.KeypadDivide -> GLFW_KEY_KP_DIVIDE;
            case ImGuiKey.KeypadMultiply -> GLFW_KEY_KP_MULTIPLY;
            case ImGuiKey.KeypadSubtract -> GLFW_KEY_KP_SUBTRACT;
            case ImGuiKey.KeypadAdd -> GLFW_KEY_KP_ADD;
            case ImGuiKey.KeypadEqual -> GLFW_KEY_KP_EQUAL;
            default -> GLFW_KEY_UNKNOWN;
        };
    }

    /** Compatibility alias; returns a GLFW key code, not an SDL scancode. */
    public static int imguiKeyToSdlScancode(int imgui) {
        return imguiKeyToGlfwKey(imgui);
    }

    public static int glfwKeyToImguiKey(int glfwKey) {
        return switch (glfwKey) {
            case GLFW_KEY_TAB -> ImGuiKey.Tab;
            case GLFW_KEY_LEFT -> ImGuiKey.LeftArrow;
            case GLFW_KEY_RIGHT -> ImGuiKey.RightArrow;
            case GLFW_KEY_UP -> ImGuiKey.UpArrow;
            case GLFW_KEY_DOWN -> ImGuiKey.DownArrow;
            case GLFW_KEY_PAGE_UP -> ImGuiKey.PageUp;
            case GLFW_KEY_PAGE_DOWN -> ImGuiKey.PageDown;
            case GLFW_KEY_HOME -> ImGuiKey.Home;
            case GLFW_KEY_END -> ImGuiKey.End;
            case GLFW_KEY_INSERT -> ImGuiKey.Insert;
            case GLFW_KEY_DELETE -> ImGuiKey.Delete;
            case GLFW_KEY_BACKSPACE -> ImGuiKey.Backspace;
            case GLFW_KEY_SPACE -> ImGuiKey.Space;
            case GLFW_KEY_ENTER -> ImGuiKey.Enter;
            case GLFW_KEY_ESCAPE -> ImGuiKey.Escape;
            case GLFW_KEY_LEFT_CONTROL -> ImGuiKey.LeftCtrl;
            case GLFW_KEY_LEFT_SHIFT -> ImGuiKey.LeftShift;
            case GLFW_KEY_LEFT_ALT -> ImGuiKey.LeftAlt;
            case GLFW_KEY_LEFT_SUPER -> ImGuiKey.LeftSuper;
            case GLFW_KEY_RIGHT_CONTROL -> ImGuiKey.RightCtrl;
            case GLFW_KEY_RIGHT_SHIFT -> ImGuiKey.RightShift;
            case GLFW_KEY_RIGHT_ALT -> ImGuiKey.RightAlt;
            case GLFW_KEY_RIGHT_SUPER -> ImGuiKey.RightSuper;
            case GLFW_KEY_MENU -> ImGuiKey.Menu;
            case GLFW_KEY_0 -> ImGuiKey._0;
            case GLFW_KEY_1 -> ImGuiKey._1;
            case GLFW_KEY_2 -> ImGuiKey._2;
            case GLFW_KEY_3 -> ImGuiKey._3;
            case GLFW_KEY_4 -> ImGuiKey._4;
            case GLFW_KEY_5 -> ImGuiKey._5;
            case GLFW_KEY_6 -> ImGuiKey._6;
            case GLFW_KEY_7 -> ImGuiKey._7;
            case GLFW_KEY_8 -> ImGuiKey._8;
            case GLFW_KEY_9 -> ImGuiKey._9;
            case GLFW_KEY_A -> ImGuiKey.A;
            case GLFW_KEY_B -> ImGuiKey.B;
            case GLFW_KEY_C -> ImGuiKey.C;
            case GLFW_KEY_D -> ImGuiKey.D;
            case GLFW_KEY_E -> ImGuiKey.E;
            case GLFW_KEY_F -> ImGuiKey.F;
            case GLFW_KEY_G -> ImGuiKey.G;
            case GLFW_KEY_H -> ImGuiKey.H;
            case GLFW_KEY_I -> ImGuiKey.I;
            case GLFW_KEY_J -> ImGuiKey.J;
            case GLFW_KEY_K -> ImGuiKey.K;
            case GLFW_KEY_L -> ImGuiKey.L;
            case GLFW_KEY_M -> ImGuiKey.M;
            case GLFW_KEY_N -> ImGuiKey.N;
            case GLFW_KEY_O -> ImGuiKey.O;
            case GLFW_KEY_P -> ImGuiKey.P;
            case GLFW_KEY_Q -> ImGuiKey.Q;
            case GLFW_KEY_R -> ImGuiKey.R;
            case GLFW_KEY_S -> ImGuiKey.S;
            case GLFW_KEY_T -> ImGuiKey.T;
            case GLFW_KEY_U -> ImGuiKey.U;
            case GLFW_KEY_V -> ImGuiKey.V;
            case GLFW_KEY_W -> ImGuiKey.W;
            case GLFW_KEY_X -> ImGuiKey.X;
            case GLFW_KEY_Y -> ImGuiKey.Y;
            case GLFW_KEY_Z -> ImGuiKey.Z;
            case GLFW_KEY_F1 -> ImGuiKey.F1;
            case GLFW_KEY_F2 -> ImGuiKey.F2;
            case GLFW_KEY_F3 -> ImGuiKey.F3;
            case GLFW_KEY_F4 -> ImGuiKey.F4;
            case GLFW_KEY_F5 -> ImGuiKey.F5;
            case GLFW_KEY_F6 -> ImGuiKey.F6;
            case GLFW_KEY_F7 -> ImGuiKey.F7;
            case GLFW_KEY_F8 -> ImGuiKey.F8;
            case GLFW_KEY_F9 -> ImGuiKey.F9;
            case GLFW_KEY_F10 -> ImGuiKey.F10;
            case GLFW_KEY_F11 -> ImGuiKey.F11;
            case GLFW_KEY_F12 -> ImGuiKey.F12;
            case GLFW_KEY_F13 -> ImGuiKey.F13;
            case GLFW_KEY_F14 -> ImGuiKey.F14;
            case GLFW_KEY_F15 -> ImGuiKey.F15;
            case GLFW_KEY_F16 -> ImGuiKey.F16;
            case GLFW_KEY_F17 -> ImGuiKey.F17;
            case GLFW_KEY_F18 -> ImGuiKey.F18;
            case GLFW_KEY_F19 -> ImGuiKey.F19;
            case GLFW_KEY_F20 -> ImGuiKey.F20;
            case GLFW_KEY_F21 -> ImGuiKey.F21;
            case GLFW_KEY_F22 -> ImGuiKey.F22;
            case GLFW_KEY_F23 -> ImGuiKey.F23;
            case GLFW_KEY_F24 -> ImGuiKey.F24;
            case GLFW_KEY_APOSTROPHE -> ImGuiKey.Apostrophe;
            case GLFW_KEY_COMMA -> ImGuiKey.Comma;
            case GLFW_KEY_MINUS -> ImGuiKey.Minus;
            case GLFW_KEY_PERIOD -> ImGuiKey.Period;
            case GLFW_KEY_SLASH -> ImGuiKey.Slash;
            case GLFW_KEY_SEMICOLON -> ImGuiKey.Semicolon;
            case GLFW_KEY_EQUAL -> ImGuiKey.Equal;
            case GLFW_KEY_LEFT_BRACKET -> ImGuiKey.LeftBracket;
            case GLFW_KEY_BACKSLASH -> ImGuiKey.Backslash;
            case GLFW_KEY_RIGHT_BRACKET -> ImGuiKey.RightBracket;
            case GLFW_KEY_GRAVE_ACCENT -> ImGuiKey.GraveAccent;
            case GLFW_KEY_CAPS_LOCK -> ImGuiKey.CapsLock;
            case GLFW_KEY_SCROLL_LOCK -> ImGuiKey.ScrollLock;
            case GLFW_KEY_NUM_LOCK -> ImGuiKey.NumLock;
            case GLFW_KEY_PRINT_SCREEN -> ImGuiKey.PrintScreen;
            case GLFW_KEY_PAUSE -> ImGuiKey.Pause;
            case GLFW_KEY_KP_0 -> ImGuiKey.Keypad0;
            case GLFW_KEY_KP_1 -> ImGuiKey.Keypad1;
            case GLFW_KEY_KP_2 -> ImGuiKey.Keypad2;
            case GLFW_KEY_KP_3 -> ImGuiKey.Keypad3;
            case GLFW_KEY_KP_4 -> ImGuiKey.Keypad4;
            case GLFW_KEY_KP_5 -> ImGuiKey.Keypad5;
            case GLFW_KEY_KP_6 -> ImGuiKey.Keypad6;
            case GLFW_KEY_KP_7 -> ImGuiKey.Keypad7;
            case GLFW_KEY_KP_8 -> ImGuiKey.Keypad8;
            case GLFW_KEY_KP_9 -> ImGuiKey.Keypad9;
            case GLFW_KEY_KP_DECIMAL -> ImGuiKey.KeypadDecimal;
            case GLFW_KEY_KP_DIVIDE -> ImGuiKey.KeypadDivide;
            case GLFW_KEY_KP_MULTIPLY -> ImGuiKey.KeypadMultiply;
            case GLFW_KEY_KP_SUBTRACT -> ImGuiKey.KeypadSubtract;
            case GLFW_KEY_KP_ADD -> ImGuiKey.KeypadAdd;
            case GLFW_KEY_KP_ENTER -> ImGuiKey.KeypadEnter;
            case GLFW_KEY_KP_EQUAL -> ImGuiKey.KeypadEqual;
            default -> ImGuiKey.None;
        };
    }

    /** Compatibility alias; accepts a GLFW key code, not an SDL scancode. */
    public static int sdlScancodeToImguiKey(int glfwKey) {
        return glfwKeyToImguiKey(glfwKey);
    }
}
