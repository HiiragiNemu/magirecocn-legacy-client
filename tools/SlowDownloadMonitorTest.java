import io.kamihama.magianative.CNChunkedDownload;
import io.kamihama.magianative.CNMirrors;
import java.io.IOException;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Runs the production monitor, not a duplicated approximation of its decisions. */
public class SlowDownloadMonitorTest {
    static final class Observer implements InvocationHandler {
        boolean cancelled, throwOnNotice;
        int notices;
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getName().equals("isCancelled")) return cancelled;
            if (method.getName().equals("onSlowTransfer")) {
                notices++;
                if (throwOnNotice) throw new IllegalStateException("test UI failure");
            }
            return null;
        }
    }
    static final class Traffic implements Runnable {
        final AtomicLong last, network, useful;
        final long start = System.nanoTime();
        final CountDownLatch done;
        Traffic(AtomicLong last, AtomicLong network, AtomicLong useful, CountDownLatch done) {
            this.last=last; this.network=network; this.useful=useful; this.done=done;
        }
        public void run() {
            last.set(System.nanoTime()); network.addAndGet(1024L); useful.addAndGet(1024L);
            if (System.nanoTime()-start >= 12500000000L) done.countDown();
        }
    }
    static void setting(String name, int value) throws Exception {
        Field f=CNMirrors.class.getDeclaredField(name); f.setAccessible(true); f.setInt(null,value);
    }
    static boolean run(boolean moving, boolean cancelled) throws Exception {
        setting("cfgMinSpeedKbps",1200); setting("cfgStallSeconds",moving?25:1);
        AtomicLong last=new AtomicLong(System.nanoTime()), bytes=new AtomicLong(), useful=new AtomicLong();
        AtomicBoolean abort=new AtomicBoolean(), open=new AtomicBoolean(true);
        AtomicReference<IOException> err=new AtomicReference<IOException>();
        CountDownLatch done=new CountDownLatch(1);
        ExecutorService pool=Executors.newSingleThreadExecutor();
        ScheduledExecutorService traffic=Executors.newSingleThreadScheduledExecutor();
        Observer obs=new Observer(); obs.cancelled=cancelled; obs.throwOnNotice=true;
        Class<?> api;
        boolean supportsNotice=true;
        try { api=Class.forName("io.kamihama.magianative.CNChunkedDownload$SlowSink"); }
        catch(ClassNotFoundException old) { api=CNChunkedDownload.Sink.class; supportsNotice=false; }
        CNChunkedDownload.Sink sink=(CNChunkedDownload.Sink) Proxy.newProxyInstance(
            api.getClassLoader(),new Class<?>[]{api},obs);
        if(moving) traffic.scheduleAtFixedRate(new Traffic(last,bytes,useful,done),0,100,TimeUnit.MILLISECONDS);
        Method monitor=CNChunkedDownload.class.getDeclaredMethod("monitor",CountDownLatch.class,
            ExecutorService.class,AtomicBoolean.class,AtomicBoolean.class,AtomicReference.class,
            AtomicLong.class,AtomicLong.class,AtomicLong.class,long.class,CNChunkedDownload.Sink.class);
        monitor.setAccessible(true);
        try { monitor.invoke(null,done,pool,abort,open,err,last,bytes,useful,1000000L,sink); }
        finally { traffic.shutdownNow(); pool.shutdownNow(); }
        boolean shouldAbort=!moving || cancelled;
        boolean ok=abort.get()==shouldAbort && (err.get()!=null)==shouldAbort && !open.get();
        if(moving && !cancelled && supportsNotice) ok &= obs.notices==1;
        System.out.println((ok?"PASS":"FAIL")+" moving="+moving+" explicitCancel="+cancelled
            +" abort="+abort.get()+" bytes="+bytes.get()+" notices="+obs.notices+" error="+err.get());
        return ok;
    }
    public static void main(String[] args) throws Exception {
        boolean ok=run(true,false);
        ok=run(false,false)&&ok;
        ok=run(true,true)&&ok;
        if(!ok) System.exit(1);
    }
}
