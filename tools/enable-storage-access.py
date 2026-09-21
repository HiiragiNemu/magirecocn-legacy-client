#!/usr/bin/env python3
"""为最终 APK 声明可由玩家手动开启的共享存储权限，不改基础树或下载路径。"""
import argparse
from pathlib import Path
import xml.etree.ElementTree as ET

ANDROID = '{http://schemas.android.com/apk/res/android}'
PERMISSION = 'android.permission.MANAGE_EXTERNAL_STORAGE'


def prepare(text):
    tree = ET.fromstring(text)
    if not any(n.get(ANDROID + 'name') == PERMISSION for n in tree.findall('uses-permission')):
        at = text.index('<application')
        text = text[:at] + '<uses-permission android:name="' + PERMISSION + '"/>\n    ' + text[at:]
    tree = ET.fromstring(text)
    assert sum(n.get(ANDROID + 'name') == PERMISSION for n in tree.findall('uses-permission')) == 1
    return text


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--tree', type=Path)
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        sample = '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="example.app"><application android:allowBackup="false"/></manifest>'
        result = prepare(sample)
        assert prepare(result) == result
        assert result.replace('<uses-permission android:name="' + PERMISSION + '"/>\n    ', '') == sample
        print('PASS: storage permission added once, application attributes unchanged')
    if args.tree:
        path = args.tree / 'AndroidManifest.xml'
        path.write_text(prepare(path.read_text(encoding='utf-8')), encoding='utf-8')
        print('PASS: final APK declares optional all-files access')
    elif not args.self_test:
        parser.error('--tree or --self-test is required')


if __name__ == '__main__':
    main()
