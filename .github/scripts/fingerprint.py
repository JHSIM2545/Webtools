#!/usr/bin/env python3
"""APK 의 '실제 내용' 지문.
 - APK 안의 매니페스트·리소스·assets (서명 META-INF 와 *.dex 는 제외)
 - 앱 코드는 dex 대신 javac 가 만든 .class 파일로 본다: dex(D8 결과)는 같은 소스로 빌드해도 실행마다 달라지지만
   .class 는 항상 같다(실측: classes2.dex 만 매번 달랐고 앱 코드가 거기 들어 있음).
같은 내용이면 항상 같은 값 → 앱 내용이 바뀐 때만 새 버전 번호를 주는 기준이 된다.
usage: fingerprint.py <apk> [--classes <intermediates/javac 폴더>] [--list]"""
import hashlib, os, sys, zipfile

apk = sys.argv[1]
cdir = sys.argv[sys.argv.index('--classes') + 1] if '--classes' in sys.argv else None
lst = '--list' in sys.argv
top = hashlib.sha256()

def add(kind, name, data):
    d = hashlib.sha256(data).hexdigest()
    top.update((kind + ':' + name + '\0' + d + '\n').encode())
    if lst:
        print(d[:12], kind, name, file=sys.stderr)   # 지문이 예상 밖으로 달라질 때 어느 파일이 다른지 로그로 비교하는 용도

z = zipfile.ZipFile(apk)
for n in sorted(z.namelist()):
    if n.endswith('/') or n.startswith('META-INF/') or n.endswith('.dex'):
        continue
    add('apk', n, z.read(n))

if cdir:
    paths = []
    for root, _, files in os.walk(cdir):
        for f in files:
            p = os.path.join(root, f).replace(os.sep, '/')
            if f.endswith('.class') and 'Debug' in p and 'Test' not in p.split('intermediates/javac/')[-1]:
                paths.append(p)
    for p in sorted(paths, key=lambda x: x.split('/classes/')[-1]):
        add('class', p.split('/classes/')[-1], open(p, 'rb').read())
    if not paths:
        sys.exit('class 파일을 찾지 못했습니다: ' + cdir)
print('fp3:' + top.hexdigest()[:16])
