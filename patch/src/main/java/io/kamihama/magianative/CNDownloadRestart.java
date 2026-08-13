package io.kamihama.magianative;

import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Per-file restart generation and active-thread registry.
 *
 * <p>A restart request never starts a second writer for the same archive. It advances the
 * generation and interrupts the current worker. The existing installer/manual task observes
 * the stale generation, discards only that file's download state, and starts the same file
 * again from byte zero. Other files keep running.</p>
 */
final class CNDownloadRestart {
    private static final int COUNT = 15;
    private static final AtomicIntegerArray GENERATION = new AtomicIntegerArray(COUNT);
    private static final AtomicReferenceArray<Thread> ACTIVE =
            new AtomicReferenceArray<Thread>(COUNT);

    private CNDownloadRestart() {}

    static int generation(int index) {
        return valid(index) ? GENERATION.get(index) : 0;
    }

    static void register(int index) {
        if (valid(index)) ACTIVE.set(index, Thread.currentThread());
    }

    static void unregister(int index) {
        if (!valid(index)) return;
        Thread current = Thread.currentThread();
        ACTIVE.compareAndSet(index, current, null);
        // A restart deliberately interrupts the worker. Do not leak that flag into the pool.
        Thread.interrupted();
    }

    static boolean request(int index) {
        if (!valid(index)) return false;
        GENERATION.incrementAndGet(index);
        Thread t = ACTIVE.get(index);
        if (t != null) {
            t.interrupt();
            return true;
        }
        return false;
    }

    static boolean changed(int index, int token) {
        return valid(index) && GENERATION.get(index) != token;
    }

    static boolean cancelled(int index, int token) {
        return Thread.currentThread().isInterrupted() || changed(index, token);
    }

    static void clearInterrupt() {
        Thread.interrupted();
    }

    private static boolean valid(int index) {
        return index >= 0 && index < COUNT;
    }
}
