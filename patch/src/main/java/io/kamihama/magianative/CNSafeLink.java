package io.kamihama.magianative;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import java.net.URI;
import java.util.Locale;

/**
 * 外链的统一出口：只放行 HTTPS，且域名必须在**写死在客户端里**的允许列表内。
 *
 * <h3>为什么需要</h3>
 *
 * 浮层上能跳出去的地方有三处，它们的地址<b>全都是云端可控的</b>：
 *
 * <ul>
 *   <li>署名条目与 GitHub 胶囊 —— 内置一份，但会被 {@code config.json} 的
 *       {@code ui_credits} / {@code github_url} 覆盖；</li>
 *   <li>右上角可变按钮的弹窗跳转 —— {@code config.json} 的
 *       {@code right_pill.url}；</li>
 *   <li>强制更新框的「前往更新」 —— {@code config.json} 的 {@code client.apk_url}。</li>
 * </ul>
 *
 * 原先三处都是直接 {@code new Intent(ACTION_VIEW, Uri.parse(url))} 就发出去，
 * 配置里写什么就跳什么。而 ACTION_VIEW 认的远不止网页：
 *
 * <ul>
 *   <li>{@code intent://…#Intent;…;end} —— 在本机拉起任意组件；</li>
 *   <li>{@code file:///data/data/…} —— 把应用私有目录交给别的应用打开；</li>
 *   <li>{@code https://www.example.test@evil.example/} —— authority 里的 userinfo
 *       让地址<b>看起来</b>是自家域名，实际连的是 evil.example；</li>
 *   <li>{@code http://} 明文站 —— 玩家看不出与正主的区别。</li>
 * </ul>
 *
 * <h3>这一层挡的到底是什么</h3>
 *
 * <b>不是中间人。</b>{@code api.example.test} 上了 DNSSEC，客户端做完整 TLS
 * 验证，网络路径上改配置这条路本来就走不通——能把内容换掉的人，基本等于已经
 * 进了服务器。所以这里挡的是另外两件更窄、但 TLS 管不了的事：
 *
 * <ol>
 *   <li><b>服务端真被攻破时的爆炸半径。</b>攻击者拿得到 {@code config.json}，
 *       却拿不到已经装在玩家机器上的客户端二进制。写死在这里的列表他改不动，
 *       于是「换个配置就能把所有在线玩家导去装任意 APK」这条最省力的路被堵上，
 *       剩下的只能是发新包——而发包要过签名那一关。</li>
 *   <li><b>配置写错。</b>scheme 打错、地址粘贴时断了行、少个斜杠，会被当场拦下
 *       并说明白哪里不对，而不是变成一次莫名其妙的 intent 调起或一个空白页。</li>
 * </ol>
 *
 * <p>说清楚这层的边界是为了不让人高估它：它<b>不</b>提供「配置可信」的保证，
 * 只保证「配置再怎么写，也只能把玩家送到这几个域名下」。
 *
 * <h3>允许列表为什么写死在客户端</h3>
 *
 * 因为要防的正是「配置内容不可信」这一种情况。允许列表若也从
 * {@code config.json} 读，改配置的人顺手把列表一起改掉就行，这层等于不存在。
 *
 * <p>代价是<b>加一个新的外链域名需要发新包</b>。这是有意选的：与
 * {@code CNMirrors#MIRRORS_URL}、签名指纹这些信任锚一样，写死才叫锚。
 * 真被拦到时日志里会明写是哪个 host 没过，一眼能看出原因，不会变成哑谜。
 */
public final class CNSafeLink {

    private static final String TAG = "MagiaCNSafeLink";

    /**
     * 允许的域名，**含子域**（{@code example.test} 覆盖 www / api / assets /
     * r2.assets / docs 等）。
     */
    private static final String[] ALLOW_DOMAINS = {
        "example.test",     // 项目自有域：官网 / api / assets / r2.assets / docs
        "bilibili.com",     // 视频教程与作者主页
        "b23.tv",           // bilibili 短链，署名区几条都是这个
        "github.com",       // ui_credits 的 github_url
    };

    /**
     * 允许的**精确主机名**，不含子域。
     *
     * <p>署名区那三个站都挂在 {@code pages}（CDN Pages）下，
     * 而那是个**公共后缀**——按域名放行等于把任何人的 Pages 站都放进来了，
     * 所以这三个只认全名。
     */
    private static final String[] ALLOW_HOSTS = {
        "reader.pages.example",
        "live2d.pages.example",
        "callsearch.pages.example",
    };

    private CNSafeLink() {}

    /**
     * 校验并调起系统浏览器。
     *
     * @param act   宿主 Activity
     * @param url   待打开的地址
     * @param what  这条外链是干什么的，只用于日志与提示（如「署名条目」「支持我们」）
     * @return 真的发出去了才返回 true
     */
    public static boolean open(Activity act, String url, String what) {
        String why = reject(url);
        if (why != null) {
            CNLog.e(TAG, "拒绝打开外链（" + what + "）: " + brief(url) + " —— " + why);
            CNCNDownloadUI.toast(act, "这个链接不安全，已拦下：" + why);
            return false;
        }
        try {
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(it);
            CNLog.i(TAG, "已调起系统浏览器（" + what + "）: " + url);
            return true;
        } catch (Throwable t) {
            CNLog.w(TAG, "打开外链失败（" + what + "）: " + url, t);
            CNCNDownloadUI.toast(act, "无法打开链接：" + url);
            return false;
        }
    }

    /**
     * 判断这个地址能不能放行。不能放行时返回**给人看的**原因，能放行返回 null。
     *
     * <p>拆成独立方法是为了能在 JVM 上直接测——它不碰任何 Android API。
     */
    public static String reject(String url) {
        if (url == null) return "地址为空";
        String s = url.trim();
        if (s.isEmpty()) return "地址为空";
        if (!s.equals(url)) return "地址首尾有空白";
        // 控制字符与空白会被某些解析器吃掉，从而让前后两段拼出与肉眼所见不同的地址
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 0x20 || c == 0x7f) return "地址里有空白或控制字符";
        }

        URI u;
        try {
            u = new URI(s);
        } catch (Throwable t) {
            return "地址格式不合法";
        }
        String scheme = u.getScheme();
        if (scheme == null) return "地址缺少协议";
        if (!"https".equals(scheme.toLowerCase(Locale.US))) {
            return "只允许 https，收到的是 " + scheme;
        }
        // userinfo：https://www.example.test@evil.example/ 这类，肉眼看着像自家域名
        if (u.getUserInfo() != null) return "地址里带用户名，可能是伪装";
        String rawAuthority = u.getRawAuthority();
        if (rawAuthority != null && rawAuthority.indexOf('@') >= 0) {
            return "地址里带用户名，可能是伪装";
        }
        String host = u.getHost();
        if (host == null || host.isEmpty()) return "地址没有主机名";
        host = host.toLowerCase(Locale.US);
        // 末尾点（"github.com."）是合法 FQDN 写法，但会绕过下面的后缀比较
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty()) return "地址没有主机名";

        if (!allowed(host)) return "域名 " + host + " 不在允许列表内";
        return null;
    }

    /** 精确主机名命中，或是某个允许域名本身/其子域。 */
    private static boolean allowed(String host) {
        for (int i = 0; i < ALLOW_HOSTS.length; i++) {
            if (host.equals(ALLOW_HOSTS[i])) return true;
        }
        for (int i = 0; i < ALLOW_DOMAINS.length; i++) {
            String d = ALLOW_DOMAINS[i];
            // 必须是 d 本身或 *.d；用 endsWith(d) 会把 "evilexample.test" 放进来
            if (host.equals(d) || host.endsWith("." + d)) return true;
        }
        return false;
    }

    /** 日志里不打完整的可疑地址，截断一下免得刷屏。 */
    private static String brief(String url) {
        if (url == null) return "null";
        return url.length() <= 120 ? url : url.substring(0, 120) + "…";
    }
}
