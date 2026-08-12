import io.kamihama.magianative.CNDownloadConcurrency;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class DownloadConcurrencyTest {
    private static int pass;
    private static int fail;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  ✓ " + name); }
        else { fail++; System.out.println("  ✗ " + name); }
    }

    public static void main(String[] args) throws Exception {
        CNDownloadConcurrency.resetPeakForTest();
        int max = CNDownloadConcurrency.maxConnectionsForTest();
        List<CNDownloadConcurrency.Lease> held =
                new ArrayList<CNDownloadConcurrency.Lease>();
        for (int i = 0; i < max; i++) held.add(CNDownloadConcurrency.acquire("test-" + i));
        check("前八个连接全部取得许可", CNDownloadConcurrency.activeForTest() == max);

        final AtomicBoolean ninthAcquired = new AtomicBoolean(false);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);
        Thread ninth = new Thread(new Runnable() {
            @Override public void run() {
                CNDownloadConcurrency.Lease lease = null;
                try {
                    started.countDown();
                    lease = CNDownloadConcurrency.acquire("ninth");
                    ninthAcquired.set(true);
                } catch (Throwable ignore) {
                } finally {
                    if (lease != null) lease.close();
                    done.countDown();
                }
            }
        }, "concurrency-test-ninth");
        ninth.start();
        started.await(1, TimeUnit.SECONDS);
        Thread.sleep(200L);
        check("第九个连接在上限处等待", !ninthAcquired.get());

        held.remove(0).close();
        check("释放一个许可后第九个可继续", done.await(2, TimeUnit.SECONDS)
                && ninthAcquired.get());
        for (CNDownloadConcurrency.Lease lease : held) lease.close();
        check("连接计数最终归零", CNDownloadConcurrency.activeForTest() == 0);
        check("实测峰值不超过八", CNDownloadConcurrency.peakForTest() <= max);

        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
