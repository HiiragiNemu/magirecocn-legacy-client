package io.kamihama.magianative;

import java.io.Closeable;

/**
 * 流的收尾杂务。目前只有一件事：安静地关掉一个 {@link Closeable}。
 *
 * <h3>为什么要单独有这么一个类</h3>
 *
 * 在此之前，同一个三行方法在五个类里各写了一份（{@code CNArchiveInstallTx}、
 * {@code CNChunkedDownload}、{@code CNDownloaderFix} 两个重载、{@code CNHotUpdate}、
 * {@code CNHotUpdateTx}），另有六处直接把 {@code try { x.close(); } catch {}} 内联在
 * {@code finally} 里。六份实现里有五份是等价的，剩下那份**不是**：
 *
 * <pre>
 *   CNDownloaderFix：catch (IOException e) {}      ← 只接 IOException
 *   其余五处      ：catch (Throwable ignore) {}    ← 全接
 * </pre>
 *
 * <p>差别不是风格。{@code close()} 抛的不止 {@code IOException}——包装流的委托对象
 * 坏掉时会冒 {@code RuntimeException}，半构造的流会冒 {@code NullPointerException}。
 * 而这个方法几乎总是从 {@code finally} 里调的：那里再抛出去，会把**原始异常整个盖
 * 掉**，留下一个和真正病因毫无关系的栈。「安静关闭」的全部意义就是不许它出声，所以
 * 统一取 {@code Throwable}。
 *
 * <p>不放进 {@code CNAtomicReplace} 或 {@code CNPaths} 之类已有的类，是因为那些类各有
 * 明确合同（原子换入、路径），塞一个通用 I/O 杂务进去会让它们的职责变模糊；而这件事
 * 本身足够独立，值得一个自己的名字。
 */
public final class CNIo {

    private CNIo() {}

    /**
     * 关掉一个流，失败一概吞掉。
     *
     * <p>{@code null} 直接返回——调用方大多是 {@code finally} 里对着一个可能压根没
     * 构造成功的局部变量，让它自己判空只会到处重复同一个 if。
     */
    public static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignore) {
            // 有意为之：见类注释。这里出声就会盖掉真正的异常。
        }
    }
}
