package io.kamihama.magianative;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercise the production continuation, including repeated UI dismissal events. */
public final class OptionalClientUpdateTest {
    private static final class Next implements Runnable {
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch done = new CountDownLatch(1);
        volatile String thread;
        public void run() {
            calls.incrementAndGet();
            thread = Thread.currentThread().getName();
            done.countDown();
        }
    }
    private static Object field(String name) throws Exception {
        Field f = CNVersionCheck.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }
    private static void bind(Next next, boolean proceeded) throws Exception {
        Field f = CNVersionCheck.class.getDeclaredField("afterPass");
        f.setAccessible(true);
        f.set(null, next);
        ((AtomicBoolean) field("PROCEEDED")).set(proceeded);
    }
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        System.out.println("PASS " + message);
    }
    private static void continueOnce(String stage) throws Exception {
        Next next = new Next();
        bind(next, false);
        check(next.calls.get() == 0, stage + ": pending prompt does not start continuation");
        CNVersionCheck.continueWithCurrentVersion();
        check(next.done.await(5, TimeUnit.SECONDS), stage + ": decline resumes continuation");
        for (int i = 0; i < 12; i++) CNVersionCheck.continueWithCurrentVersion();
        Thread.sleep(150);
        check(next.calls.get() == 1, stage + ": repeated close/back/button events run once");
        check("cnv-version-continue".equals(next.thread), stage + ": continuation stays off UI thread");
    }
    public static void main(String[] args) throws Exception {
        continueOnce("installed resource-check callback");
        continueOnce("first-install callback");
        Next done = new Next();
        bind(done, true);
        CNVersionCheck.continueWithCurrentVersion();
        Thread.sleep(150);
        check(done.calls.get() == 0, "late dismissal does not rerun completed startup");
        check(CNVersionCheck.compareVersion("1.0.187", "1.0.188") < 0,
                "newer versions remain discoverable; version check is not disabled");
        check(CNVersionCheck.compareVersion("1.0.187", "1.0.186") > 0,
                "version ordering preserved");
    }
}
