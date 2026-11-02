#!/usr/bin/env python3
"""按仓库内冻结清单准备Windows离线运行时，已有正确文件原样复用。"""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[2]


def prepare():
    """只下载公开安装产物；校验不通过时停止，不执行未经确认的文件。"""
    downloads = ROOT / '.tools/downloads'
    downloads.mkdir(parents=True, exist_ok=True)
    manifest_path = ROOT / 'deploy/windows/artifacts.json'
    manifest = json.loads(manifest_path.read_text())
    for name, entry in manifest.items():
        target = downloads / entry['filename']
        if target.exists() and hashlib.file_digest(target.open('rb'), 'sha256').hexdigest() == entry['sha256']:
            continue
        temporary = target.with_suffix(target.suffix + '.partial')
        with urllib.request.urlopen(entry['url'], timeout=90) as response, temporary.open('wb') as output:
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
        with temporary.open('rb') as handle:
            valid = hashlib.file_digest(handle, 'sha256').hexdigest() == entry['sha256']
        if not valid or temporary.stat().st_size != entry['size']:
            raise RuntimeError(name + '下载校验失败，文件未启用')
        temporary.replace(target)
        print(name + ' verified', flush=True)
    (downloads / 'windows-downloads.json').write_text(manifest_path.read_text())
    wheels = ROOT / '.tools/wheels-win'
    wheels.mkdir(exist_ok=True)
    requirements = ROOT / 'deploy/windows/requirements-windows.lock'
    binary = downloads / 'windows-binary-requirements.txt'
    binary.write_text('\n'.join(line for line in requirements.read_text().splitlines()
                               if not line.startswith('jsonpath==')) + '\n')
    subprocess.run([sys.executable, '-m', 'pip', 'download', '--only-binary=:all:',
                    '--platform', 'win_amd64', '--python-version', '312', '--implementation', 'cp',
                    '--abi', 'cp312', '--dest', str(wheels), '-r', str(binary)], check=True)
    # jsonpath只有源码发布，生成纯Python通用wheel；不在Windows现场编译。
    subprocess.run([sys.executable, '-m', 'pip', 'wheel', '--no-deps', '--wheel-dir', str(wheels),
                    'jsonpath==0.82.2'], check=True)


if __name__ == '__main__':
    prepare()
