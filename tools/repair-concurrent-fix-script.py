#!/usr/bin/env python3
from pathlib import Path

p = Path('tools/apply-concurrent-redownload-fix.py')
s = p.read_text(encoding='utf-8')
start = s.index('# Long single-stream base transfers also participate in the global eight-connection gate.')
end = s.index("\ninsert = r'''", start)
replacement = r"""# Long single-stream base transfers also participate in the global eight-connection gate.
# Restrict the replacement to downloadOnce: the same URL/openConnection text is also used
# by postJson, which is a tiny control request and must not consume the large-transfer gate.
method_at = s.index('    private static DownloadMetadata downloadOnce')
method_end = s.index('\n    // ==================================================================\n    // 解压', method_at)
head, method, tail = s[:method_at], s[method_at:method_end], s[method_end:]
old = '''        URL u = new URL(url);
        HttpURLConnection c = (HttpURLConnection)
                (direct ? u.openConnection(Proxy.NO_PROXY) : u.openConnection());'''
new = '''        CNDownloadConcurrency.Lease networkLease =
                CNDownloadConcurrency.acquire("base-single:" + archive.getName());
        URL u = new URL(url);
        HttpURLConnection c = (HttpURLConnection)
                (direct ? u.openConnection(Proxy.NO_PROXY) : u.openConnection());'''
if method.count(old) != 1:
    raise SystemExit(f'base single-stream lease open baseline changed: {method.count(old)}')
method = method.replace(old, new, 1)
old = '''            closeQuietly(out);
            closeQuietly(in);
            c.disconnect();
        }
    }'''
new = '''            closeQuietly(out);
            closeQuietly(in);
            c.disconnect();
            networkLease.close();
        }
    }'''
if method.count(old) != 1:
    raise SystemExit(f'base single-stream lease finally baseline changed: {method.count(old)}')
method = method.replace(old, new, 1)
s = head + method + tail
"""
p.write_text(s[:start] + replacement + s[end:], encoding='utf-8')
