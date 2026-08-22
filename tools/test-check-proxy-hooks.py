#!/usr/bin/env python3
"""回归：native API/chat 只能只读观测，不能重新接回端点改写。"""

import importlib.util
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CHECKER_PATH = ROOT / "tools" / "check-proxy-hooks.py"
SOURCE_PATH = ROOT / "magia-native" / "src" / "MagiaLegacy.cpp"


def load_checker():
    spec = importlib.util.spec_from_file_location("check_proxy_hooks", CHECKER_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def replace_once(text, old, new):
    if text.count(old) != 1:
        raise AssertionError("测试夹具锚点数量不是 1：%r" % old)
    return text.replace(old, new, 1)


def main():
    checker = load_checker()
    source = SOURCE_PATH.read_text(encoding="utf-8")

    assert checker.validate(source) == [], checker.validate(source)

    api_bad = replace_once(
        source,
        'return endpointObserveOnly(urlConfigApiOld, self, type, 0, "api(只读)");',
        'return endpointRewrite(urlConfigApiOld, self, type, 0, "api");',
    )
    api_errors = checker.validate(api_bad)
    assert any("urlConfigApiNew" in e and "336142563" in e for e in api_errors), api_errors

    chat_bad = replace_once(
        source,
        'return endpointObserveOnly(urlConfigChatOld, self, type, 2, "chat(只读)");',
        'return endpointRewrite(urlConfigChatOld, self, type, 2, "chat");',
    )
    chat_errors = checker.validate(chat_bad)
    assert any("urlConfigChatNew" in e and "336142563" in e for e in chat_errors), chat_errors

    api_no_observe = replace_once(
        source,
        'return endpointObserveOnly(urlConfigApiOld, self, type, 0, "api(只读)");',
        'return urlConfigApiOld(self, type);',
    )
    errors = checker.validate(api_no_observe)
    assert any("urlConfigApiNew" in e and "endpointObserveOnly" in e for e in errors), errors

    print("✔ native 端点直连防回归 4/4 通过")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
