package dev.airun.storagefill;

public final class RequestLimits {
    public static final int MAX_SLOTS = 81;
    public static final int MAX_CANDIDATES = 512;
    public static final int MAX_SNAPSHOT = 1024;
    public static final int MAX_SNAPSHOT_BYTES = 262144;
    public static final int MAX_SESSION_STACKS = 65536;
    public static final int MAX_SESSION_BYTES = 16777216;
    public static final int MAX_COUNT = 5184;

    private RequestLimits() { }

    public static boolean validSlots(int size, int[] targets, int[] sources) {
        if (targets.length == 0 || targets.length > MAX_SLOTS || sources.length > 256) return false;
        java.util.Set<Integer> used = new java.util.HashSet<>();
        for (int slot : targets) {
            if (slot < 0 || slot >= size || !used.add(slot)) return false;
        }
        for (int slot : sources) {
            if (slot < 0 || slot >= size || !used.add(slot)) return false;
        }
        return true;
    }
}
