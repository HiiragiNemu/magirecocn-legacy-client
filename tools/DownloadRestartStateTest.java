package io.kamihama.magianative;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

public final class DownloadRestartStateTest {
    private static int pass;
    private static int fail;

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  ✓ " + name);
        } else {
            fail++;
            System.out.println("  ✗ " + name);
        }
    }

    public static void main(String[] args) throws Exception {
        final int index = 0;
        CNDownloadRestart.resetForTest(index);

        int g1 = CNDownloadRestart.publishForTest(index, true);
        check("一次发布同时更新 generation", g1 == 1
                && CNDownloadRestart.generation(index) == 1);
        check("一次发布同时更新 reason",
                CNDownloadRestart.keepOfflineRequested(index));

        int g2 = CNDownloadRestart.publishForTest(index, false);
        check("后一次发布完整覆盖前一次状态", g2 == 2
                && CNDownloadRestart.generation(index) == 2
                && !CNDownloadRestart.keepOfflineRequested(index));

        System.out.println("[并发] 最终 reason 必须属于最终 generation");
        for (int round = 0; round < 100; round++) {
            CNDownloadRestart.resetForTest(index);
            final int threads = 32;
            final int each = 200;
            final CountDownLatch start = new CountDownLatch(1);
            final CountDownLatch done = new CountDownLatch(threads);
            final Map<Integer, Boolean> published =
                    new ConcurrentHashMap<Integer, Boolean>();

            for (int t = 0; t < threads; t++) {
                final int tid = t;
                Thread worker = new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            start.await();
                            for (int i = 0; i < each; i++) {
                                boolean reason = ((tid + i) & 1) == 0;
                                int generation =
                                        CNDownloadRestart.publishForTest(index, reason);
                                published.put(Integer.valueOf(generation),
                                        Boolean.valueOf(reason));
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    }
                });
                worker.start();
            }

            start.countDown();
            done.await();
            int generation = CNDownloadRestart.generation(index);
            Boolean expected = published.get(Integer.valueOf(generation));
            boolean actual = CNDownloadRestart.keepOfflineRequested(index);
            if (generation != threads * each
                    || expected == null
                    || expected.booleanValue() != actual) {
                check("并发发布 round=" + round, false);
                break;
            }
            if (round == 99) check("100 轮并发发布保持 generation/reason 原子一致", true);
        }

        check("非法槽位保持安全默认值",
                CNDownloadRestart.publishForTest(-1, true) == 0
                && CNDownloadRestart.generation(-1) == 0
                && !CNDownloadRestart.keepOfflineRequested(-1));

        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
