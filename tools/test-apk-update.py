#!/usr/bin/env python3
"""Run the real pure-Java APK transfer implementation against bounded fixtures."""
import os,pathlib,shutil,subprocess,tempfile
r=pathlib.Path(__file__).resolve().parents[1]
java=pathlib.Path(os.environ['JAVA_HOME'])/'bin' if os.environ.get('JAVA_HOME') else None
def exe(name):return str(java/(name+('.exe' if os.name=='nt' else ''))) if java else name
with tempfile.TemporaryDirectory(prefix='apk-update-classes-') as d:
    sources=[r/'patch/src/main/java/io/kamihama/magianative/CNApkDownload.java',r/'tools/ApkUpdateDownloadTest.java']
    subprocess.run([exe('javac'),'-encoding','UTF-8','-source','8','-target','8','-d',d,*map(str,sources)],check=True)
    subprocess.run([exe('java'),'-cp',d,'io.kamihama.magianative.ApkUpdateDownloadTest'],check=True)
