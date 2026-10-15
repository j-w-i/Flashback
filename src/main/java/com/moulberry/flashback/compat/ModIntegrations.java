package com.moulberry.flashback.compat;

import com.moulberry.flashback.Flashback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;

/**
 * Carries per-recording state belonging to other mods through a replay.
 *
 * <p>A replay is played by Flashback's own replay server, not by the server the recording was made on, so any
 * mod whose client behaviour depends on server-side facts (which world type, which ruleset) cannot work them
 * out again during playback or export. The recording therefore stores a small string per integration and
 * playback hands it back to that mod.
 *
 * <p>Integrations are reached reflectively and only when the mod is present, so Flashback keeps no compile or
 * runtime dependency on any of them, and a replay recorded with an integration still opens without it.
 */
public final class ModIntegrations {

    /** The Minecraft Version Emulator: its active historical version drives HUD layout and rules. */
    public static final String VERSION_EMULATOR = "version_emulator";
    private static final String EMULATOR_MOD_ID = "hex1211";
    private static final String EMULATOR_RULES_CLASS = "net.historicalexperience.client.HistoricalClientRules";
    private static final String EMULATOR_SESSION_CLASS = "net.historicalexperience.gameplay.HistoricalActiveSession";

    @Nullable
    private static String appliedEmulatorVersion;

    private ModIntegrations() {
    }

    /** Collects the state to store in a new recording's metadata. */
    public static LinkedHashMap<String, String> capture() {
        LinkedHashMap<String, String> state = new LinkedHashMap<>();
        String emulator = readEmulatorVersion();
        if (emulator != null) {
            state.put(VERSION_EMULATOR, emulator);
        }
        return state;
    }

    /**
     * Makes this client present a replay the way it was recorded. Idempotent and cheap, so it can run every
     * tick: the emulator's own server join hook also writes its session value while the replay world loads, and
     * that write has to be overridden afterwards.
     */
    public static void applyForReplay(@Nullable java.util.Map<String, String> state) {
        String wanted = state == null ? null : state.get(VERSION_EMULATOR);
        if (wanted == null || !FabricLoader.getInstance().isModLoaded(EMULATOR_MOD_ID)) {
            return;
        }
        if (wanted.equals(readEmulatorVersion())) {
            appliedEmulatorVersion = wanted;
            return;
        }
        if (writeEmulatorVersion(wanted)) {
            appliedEmulatorVersion = wanted;
            Flashback.LOGGER.info("Applied recorded version-emulator version {} to replay playback", wanted);
        }
    }

    /** Undoes {@link #applyForReplay} when the replay is closed, so an ordinary world is not left in that era. */
    public static void clearAfterReplay() {
        if (appliedEmulatorVersion == null) {
            return;
        }
        appliedEmulatorVersion = null;
        writeEmulatorVersion(null);
    }

    private static final String EMULATOR_HUD_CLASS = "net.historicalexperience.client.hud.HudHelper";
    @Nullable
    private static Method emulatorHideExperience;
    private static boolean emulatorHudLookedUp;

    /**
     * Whether the Version Emulator says the active era has no experience bar. Flashback decides bar visibility from
     * the watched player's game mode, which runs before (and would override) the emulator's own gate, so the
     * question has to be put to it directly or eras without XP would grow an empty bar in replays and exports.
     */
    public static boolean emulatorHidesExperienceBar() {
        if (!emulatorHudLookedUp) {
            emulatorHudLookedUp = true;
            if (FabricLoader.getInstance().isModLoaded(EMULATOR_MOD_ID)) {
                try {
                    emulatorHideExperience = Class.forName(EMULATOR_HUD_CLASS).getMethod("hideExperienceBar");
                } catch (Throwable t) {
                    Flashback.LOGGER.warn("Version emulator HUD gate not found", t);
                }
            }
        }
        if (emulatorHideExperience == null) {
            return false;
        }
        try {
            return (Boolean) emulatorHideExperience.invoke(null);
        } catch (Throwable t) {
            return false;
        }
    }

    @Nullable
    public static String readEmulatorVersion() {
        if (!FabricLoader.getInstance().isModLoaded(EMULATOR_MOD_ID)) {
            return null;
        }
        try {
            Method current = Class.forName(EMULATOR_SESSION_CLASS).getMethod("current");
            return (String) current.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean writeEmulatorVersion(@Nullable String version) {
        try {
            // The emulator's own "server pushed a version" entry points. They set the session value too, but also the
            // client-side answer that the HUD, the texture stack and the screens ask. A replay has no historical world
            // to answer from, so setting only the session left the textures and the recipe-book gate on "unknown".
            Class<?> rules = Class.forName(EMULATOR_RULES_CLASS);
            if (version == null) {
                rules.getMethod("clearSyncedVersion").invoke(null);
            } else {
                rules.getMethod("applySyncedVersion", String.class).invoke(null, version);
            }
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException old) {
            // An emulator build from before those existed: the session value is all there is.
            try {
                Class<?> session = Class.forName(EMULATOR_SESSION_CLASS);
                if (version == null) {
                    session.getMethod("clear").invoke(null);
                } else {
                    session.getMethod("set", String.class).invoke(null, version);
                }
                return true;
            } catch (Throwable t) {
                Flashback.LOGGER.warn("Could not update version-emulator session", t);
                return false;
            }
        } catch (Throwable t) {
            Flashback.LOGGER.warn("Could not update version-emulator session", t);
            return false;
        }
    }
}
