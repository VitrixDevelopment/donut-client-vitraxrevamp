package dev.donut.client.sound;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayDeque;

/** A successful client prediction is only a probable self match, never server acknowledgement. */
public final class LocalActionTracker {
    private record Action(long nanos, double x, double y, double z, String category) { }
    private static final ArrayDeque<Action> actions = new ArrayDeque<>();
    private LocalActionTracker() { }
    public static void record(BlockPos target, String category) {
        if (!SoundDetectorController.enabled() || target == null) return;
        recordAt(target, category, System.nanoTime());
    }
    static void recordAt(BlockPos target, String category, long nanos) {
        while (actions.size() >= 128) actions.removeFirst();
        actions.addLast(new Action(nanos, target.getX()+.5, target.getY()+.5, target.getZ()+.5, category));
    }
    public static void record(Vec3 target, String category) {
        if (!SoundCapture.active() || target == null) return;
        while (actions.size() >= 128) actions.removeFirst();
        actions.addLast(new Action(System.nanoTime(), target.x, target.y, target.z, category));
    }
    public static boolean matches(double x, double y, double z, String category, long now) {
        SoundDetectorController controller = SoundDetectorController.get();
        double seconds = controller == null ? 1 : controller.settings().selfWindowSeconds;
        return matches(x, y, z, category, now, seconds);
    }
    static boolean matches(double x, double y, double z, String category, long now, double seconds) {
        while (!actions.isEmpty() && now - actions.peekFirst().nanos > 3_000_000_000L) actions.removeFirst();
        long window = (long) (Math.clamp(Double.isFinite(seconds) ? seconds : 1, .25, 3) * 1_000_000_000L);
        for (Action action : actions) {
            if (now - action.nanos > window || !action.category.equals(category)) continue;
            double dx = x - action.x, dy = y - action.y, dz = z - action.z;
            if (dx * dx + dy * dy + dz * dz <= .765625) return true;
        }
        return false;
    }
    public static void clear() { actions.clear(); }
}
