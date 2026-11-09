package dev.apklab.runner.session;

import java.lang.management.ManagementFactory;

/**
 * The VM's memory budget for memory "automatic", as the site computes it for a visitor on this machine. The lab's heapLimitFor (runtime/memory-budget.js) follows
 * navigator.deviceMemory, which Node lacks; the sidecar's Worker shim (sidecar/lib/vm-worker.mjs, sidecar/lib/memory.mjs) defines it as Chrome would: the RAM in GiB
 * rounded down to a power of two, clamped to [0.25, 8]. This class mirrors that rule for /health (the sidecar reports the same numbers for each session).
 */
public final class MemoryBudget {
    public static final int BYTES_PER_OBJECT = 1600, MIN_OBJECTS = 65536, MAX_OBJECTS = 2097152, DEFAULT_OBJECTS = 262144;
    private static final double GIB = 1024d * 1024 * 1024;
    private static final long MIB = 1024L * 1024;

    private MemoryBudget() {}

    public record Report(int budgetMiB, int maxHeapObjects, String source, Double deviceMemory, int workerHeapMb) {}

    /** navigator.deviceMemory for a machine with {@code totalBytes} of RAM, or null when unknown. */
    public static Double deviceMemoryFor(long totalBytes) {
        if (totalBytes <= 0) return null;
        double gib = totalBytes / GIB;
        return Math.min(8, Math.max(0.25, Math.pow(2, Math.floor(Math.log(gib) / Math.log(2)))));
    }

    /** The RAM of this machine (the JDK reports a container's limit when one is set), or -1. */
    public static long machineMemoryBytes() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) return os.getTotalMemorySize();
        } catch (RuntimeException | LinkageError ignored) { /* not available */ }
        return -1;
    }

    /** The lab's memoryBudget: a configured step wins, else deviceMemory × 128 MiB in [96, 1024], else the lab's Node default; objects = budget / 1600 bytes. */
    public static Report budget(int memoryMiB, Double deviceMemory, int vmHeapMb, int vmHeapMaxMb) {
        int budgetMiB; String source; Double device = null;
        if (memoryMiB > 0) { budgetMiB = memoryMiB; source = "configured"; }
        else if (deviceMemory != null && deviceMemory > 0) {
            budgetMiB = (int) Math.min(1024, Math.max(96, Math.round(deviceMemory * 128)));
            source = "navigator.deviceMemory=" + (deviceMemory == Math.rint(deviceMemory) ? String.valueOf(deviceMemory.longValue()) : String.valueOf(deviceMemory));
            device = deviceMemory;
        } else { budgetMiB = (int) Math.floor((double) DEFAULT_OBJECTS * BYTES_PER_OBJECT / MIB); source = "default"; }
        int objects = (int) Math.min(MAX_OBJECTS, Math.max(MIN_OBJECTS, Math.floor(budgetMiB * (double) MIB / BYTES_PER_OBJECT)));
        return new Report(budgetMiB, objects, source, device, workerHeapMb(budgetMiB, vmHeapMb, vmHeapMaxMb));
    }

    /** JS heap (MB) of the VM Worker: twice the budget plus 1 GiB, at least RUNNER_VM_HEAP_MB, at most RUNNER_VM_HEAP_MAX_MB (the sidecar uses the same rule). */
    public static int workerHeapMb(int budgetMiB, int vmHeapMb, int vmHeapMaxMb) {
        return Math.min(vmHeapMaxMb, Math.max(vmHeapMb, budgetMiB * 2 + 1024));
    }

    /** The budget of a default session (memory "automatic") on this machine. */
    public static Report automatic(int vmHeapMb, int vmHeapMaxMb) {
        return budget(0, deviceMemoryFor(machineMemoryBytes()), vmHeapMb, vmHeapMaxMb);
    }
}
