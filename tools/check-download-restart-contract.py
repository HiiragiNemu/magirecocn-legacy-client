#!/usr/bin/env python3
from pathlib import Path

src = Path(
    "patch/src/main/java/io/kamihama/magianative/CNDownloadRestart.java"
).read_text(encoding="utf-8")

checks = {
    "generation 与 reason 使用单一不可变状态":
        "AtomicReferenceArray<RequestState> REQUESTS" in src
        and "final int generation;" in src
        and "final boolean keepOffline;" in src,
    "请求通过单一 CAS 线性化":
        "REQUESTS.compareAndSet(index, old, next)" in src,
    "不再保留分离的 GENERATION 数组":
        "AtomicIntegerArray GENERATION" not in src,
    "不再保留分离的 KEEP_OFFLINE 数组":
        "AtomicIntegerArray KEEP_OFFLINE" not in src,
    "request/register/unregister 共用槽位锁":
        "Object[] SLOT_LOCKS" in src
        and src.count("synchronized (SLOT_LOCKS[index])") >= 4,
    "消费 restart 会推进 owner acknowledgement":
        "owner.acknowledgedGeneration = REQUESTS.get(i).generation" in src,
    "未消费普通重下在 owner 注销后补排":
        "schedulePendingRestart(index, pending.generation)" in src
        and "CNManualRedownload.request(null, index)" in src,
    "离线接管不被普通补排覆盖":
        "pending != null && !pending.keepOffline" in src,
    "默认 request 也走原子发布":
        "return request(index, false);" in src,
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS " if ok else "FAIL ") + name)
if failed:
    raise SystemExit("download restart state contract failed: " + ", ".join(failed))
