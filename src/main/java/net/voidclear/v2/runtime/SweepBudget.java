package net.voidclear.v2.runtime;

/** Conservative next-batch cap based on summed owner-thread work, excluding scheduling/DB wait. */
final class SweepBudget {
    private final boolean adaptive;
    private final int max;
    private final int min;
    private final long target;
    private int limit;

    SweepBudget(boolean adaptive, int max, int min, long target) {
        this.adaptive = adaptive;
        this.max = Math.max(1, max);
        this.min = Math.min(this.max, Math.max(1, min));
        this.target = Math.max(1, target);
        limit = adaptive ? this.min : this.max;
    }

    int limit() { return limit; }

    void record(long nanos) {
        if (!adaptive) return;
        if (nanos > target) limit = Math.max(min, limit / 2);
        else if (nanos < target / 2)
            limit = (int) Math.min(max, (long) limit + Math.max(1, limit / 4));
    }
}
