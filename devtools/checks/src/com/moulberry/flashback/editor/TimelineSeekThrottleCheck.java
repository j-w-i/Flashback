package com.moulberry.flashback.editor;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dragging the timeline must not ask the replay for a position on every frame.
 *
 * <p>Each seek is expensive: a backwards one rewinds the replay to the start of the recording chunk and plays it
 * forward again (up to {@code Recorder.CHUNK_LENGTH_SECONDS} of footage). Asked for once per frame, a drag keeps the
 * replay busy with positions nobody looks at and delays play. The rule is checked against the code rather than by
 * dragging, because the cost only shows up on a long recording.
 */
public class TimelineSeekThrottleCheck {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : "src/main/java");
        Path file = root.resolve("com/moulberry/flashback/editor/ui/windows/TimelineWindow.java");
        String source = Files.readString(file);

        check("a drag uses the throttled seek, not the immediate one",
            source.contains("seekDuringDrag(f, snapTick(f, f.tickAtX(f.mouseX)))"));
        check("it is throttled by an interval",
            source.contains("DRAG_SEEK_INTERVAL_NANOS") && source.contains("lastDragSeekNanos"));
        check("the interval is a fraction of a second, not every frame",
            source.contains("150_000_000L"));
        check("the position the pointer is released on is asked for at once",
            source.contains("lastDragSeekNanos = 0;") && source.contains("flushPendingSeek(f);"));
        check("a seek is not asked for while the replay is taking another",
            source.contains("f.replayServer.jumpToTick >= 0 || f.replayServer.fastForwarding"));
        check("a pending position cannot leak into another replay",
            source.contains("lastReplayIdentifier"));

        if (failures > 0) {
            System.out.println("FAILURES: " + failures);
            System.exit(1);
        }
        System.out.println("All timeline seek throttle checks passed");
    }

    private static void check(String what, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }
}
