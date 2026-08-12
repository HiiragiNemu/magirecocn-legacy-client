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
head, tail = s[:method_at], s[method_at:]
old = '''        URL u = new URL(url);
        HttpURLConnection c = (HttpURLConnection)
                (direct ? u.openConnection(Proxy.NO_PROXY) : u.openConnection());'''
new = '''        CNDownloadConcurrency.Lease networkLease =
                CNDownloadConcurrency.acquire("base-single:" + archive.getName());
        URL u = new URL(url);
        HttpURLConnection c = (HttpURLConnection)
                (direct ? u.openConnection(Proxy.NO_PROXY) : u.openConnection());'''
if tail.count(old) != 1:
    raise SystemExit(f'base single-stream lease open baseline changed: {tail.count(old)}')
tail = tail.replace(old, new, 1)
old = '''            closeQuietly(out);
            closeQuietly(in);
            c.disconnect();
        }
    }

    // ==================================================================
    // 解压'''
new = '''            closeQuietly(out);
            closeQuietly(in);
            c.disconnect();
            networkLease.close();
        }
    }

    // ==================================================================
    // 解压'''
if tail.count(old) != 1:
    raise SystemExit(f'base single-stream lease finally baseline changed: {tail.count(old)}')
tail = tail.replace(old, new, 1)
s = head + tail
"""
p.write_text(s[:start] + replacement + s[end:], encoding='utf-8')
