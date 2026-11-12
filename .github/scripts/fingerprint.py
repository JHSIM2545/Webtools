#!/usr/bin/env python3
"""APK 의 '실제 내용' 지문. 서명(META-INF)을 뺀 모든 파일(코드·매니페스트·리소스·assets)을 해시한다.
같은 내용이면 항상 같은 값 → 앱 내용이 바뀐 때만 새 버전 번호를 주는 기준이 된다.
usage: fingerprint.py <apk> [--list]"""
import hashlib, sys, zipfile

z = zipfile.ZipFile(sys.argv[1])
top = hashlib.sha256()
for n in sorted(z.namelist()):
    if n.endswith('/') or n.startswith('META-INF/'):
        continue
    d = hashlib.sha256(z.read(n)).hexdigest()
    top.update(n.encode() + b'\0' + d.encode() + b'\n')
    if '--list' in sys.argv:
        print(d[:12], n, file=sys.stderr)   # 지문이 예상 밖으로 달라질 때 어느 파일이 다른지 로그로 비교하는 용도
print('fp:' + top.hexdigest()[:16])
