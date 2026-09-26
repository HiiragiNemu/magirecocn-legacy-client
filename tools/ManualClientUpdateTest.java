package io.kamihama.magianative;

import org.json.JSONObject;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

/** Manual checks use the real version/metadata rules without invoking startup. */
public final class ManualClientUpdateTest {
    static void expect(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        System.out.println("PASS " + label);
    }
    static JSONObject client(String version) throws Exception {
        return new JSONObject().put("version", version).put("size", 123)
                .put("sha256", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                .put("apk_url", "https://github.com/example/releases/download/latest/client.apk");
    }
    static AtomicBoolean flag(String name) throws Exception {
        Field f = CNVersionCheck.class.getDeclaredField(name);
        f.setAccessible(true);
        return (AtomicBoolean) f.get(null);
    }
    public static void main(String[] args) throws Exception {
        expect(CNVersionCheck.manualResult("1.0.192", client("1.0.193")) == 1, "newer APK offers update");
        expect(CNVersionCheck.manualResult("1.0.193", client("1.0.193")) == 0, "equal APK is current");
        expect(CNVersionCheck.manualResult("1.0.193", client("1.0.171")) == 0, "older source never downgrades");
        expect(CNVersionCheck.manualResult("1.0.193", null) == -1, "missing response is a retry, not latest");
        expect(CNVersionCheck.manualResult(null, client("1.0.193")) == -1, "missing local version is a retry");
        expect(CNVersionCheck.manualResult("bad", client("1.0.193")) == -1, "invalid local version rejected");
        expect(CNVersionCheck.manualResult("1.0.192", client("1.0.193").put("sha256", "")) == -1, "missing hash rejected");
        expect(CNVersionCheck.manualResult("1.0.192", client("1.0.193").put("size", 0)) == -1, "empty package rejected");
        expect(CNVersionCheck.manualResult("1.0.192", client("1.0.193").put("apk_url", "http://github.com/x.apk")) == -1, "existing link rules preserved");
        flag("STARTED").set(true); flag("PROCEEDED").set(true);
        for (int i=0; i<3; i++) {
            expect(CNVersionCheck.manualResult("1.0.192", client("1.0.193")) == 1, "repeatable manual decision " + i);
            CNVersionCheck.checkManually(null);
        }
        expect(flag("STARTED").get() && flag("PROCEEDED").get(), "manual decision preserves startup gates");
        expect(!CNVersionCheck.manualCheckInProgress(), "absent Activity does not leave busy flag set");
    }
}
