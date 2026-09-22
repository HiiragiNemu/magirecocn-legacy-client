#!/usr/bin/env python3
"""构建时同步设置共享存储权限及入口开关；当前发布默认关闭，不改下载路径。"""
import argparse
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ANDROID = '{http://schemas.android.com/apk/res/android}'
PERMISSION = 'android.permission.MANAGE_EXTERNAL_STORAGE'


def prepare(text, enabled=False):
    tree = ET.fromstring(text)
    if not enabled:
        text = re.sub(r'<uses-permission\b[^>]*\bandroid:name\s*=\s*([\"\x27])'
                      + re.escape(PERMISSION) + r'\1[^>]*/>\s*', '', text)
    elif not any(n.get(ANDROID + 'name') == PERMISSION for n in tree.findall('uses-permission')):
        at = text.index('<application')
        text = text[:at] + '<uses-permission android:name="' + PERMISSION + '"/>\n    ' + text[at:]
    tree = ET.fromstring(text)
    assert sum(n.get(ANDROID + 'name') == PERMISSION for n in tree.findall('uses-permission')) == int(enabled)
    return text


def configure(text, enabled=False):
    text, count = re.subn(r'(boolean ALL_FILES_ACCESS\s*=\s*)(?:true|false)(;)',
                         lambda m: m[1] + str(enabled).lower() + m[2], text)
    assert count == 1, 'Missing or repeated ALL_FILES_ACCESS build flag'
    return text


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--tree', type=Path)
    parser.add_argument('--self-test', action='store_true')
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--enable', action='store_true')
    mode.add_argument('--disable', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        sample = '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="example.app"><application android:allowBackup="false"/></manifest>'
        result = prepare(sample, True)
        assert prepare(result, True) == result
        assert result.replace('<uses-permission android:name="' + PERMISSION + '"/>\n    ', '') == sample
        assert prepare(sample) == sample
        assert prepare(result) == sample
        assert prepare(prepare(result)) == sample
        unrelated = '<uses-permission android:name="android.permission.INTERNET"/>'
        mixed = result.replace('<application', unrelated + '<application')
        assert unrelated in prepare(mixed)
        assert PERMISSION not in prepare(mixed)
        flag = 'public static final boolean ALL_FILES_ACCESS = false;'
        assert configure(configure(flag, True)) == flag
        assert 'true' in configure(flag, True)
        print('PASS: disabled removes permission; enabled retained; unrelated permissions and application unchanged; flag synchronized')
    if args.tree:
        path = args.tree / 'AndroidManifest.xml'
        path.write_text(prepare(path.read_text(encoding='utf-8'), args.enable), encoding='utf-8')
        flag = Path(__file__).resolve().parents[1] / 'patch/src/main/java/io/kamihama/magianative/CNBuildConfig.java'
        flag.write_text(configure(flag.read_text(encoding='utf-8'), args.enable), encoding='utf-8')
        print('PASS: final APK all-files access and UI entry ' + ('enabled' if args.enable else 'disabled'))
    elif not args.self_test:
        parser.error('--tree or --self-test is required')


if __name__ == '__main__':
    main()
