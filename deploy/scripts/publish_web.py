#!/usr/bin/env python3
"""部署Windows独立HTTPS入口；所有安装包按官方来源及冻结SHA256核验。"""
import argparse
import hashlib
import json
import urllib.request

from publish import ROOT, connect, remote, upload


def prepare():
    """下载清单中的公开产物并核对摘要，不读取管理员口令或证书私钥。"""
    folder = ROOT / '.runtime/public-web-artifacts'
    folder.mkdir(exist_ok=True)
    manifest = json.loads((ROOT / 'deploy/windows/public-web-artifacts.json').read_text())
    for entry in manifest.values():
        path = folder / entry['filename']
        if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest() != entry['sha256']:
            with urllib.request.urlopen(entry['url'], timeout=90) as response:
                data = response.read()
            if len(data) != entry['size'] or hashlib.sha256(data).hexdigest() != entry['sha256']:
                raise RuntimeError('公开Web安装包校验失败，未上传或执行')
            path.write_bytes(data)
    return folder, manifest


def main():
    """先安装仅提供域名校验的入口，签发证书后由服务器脚本启用HTTPS。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('--host', default='example.invalid')
    parser.add_argument('--install-only', action='store_true')
    args = parser.parse_args()
    folder, manifest = prepare()
    with connect(args.host, 'Administrator') as client, client.open_sftp() as sftp:
        for entry in manifest.values():
            upload(sftp, folder / entry['filename'],
                   'C:/ETFQuantLab/staging/public-web/' + entry['filename'])
        for name in ['public-web-artifacts.json', 'nginx-bootstrap.conf', 'nginx-https.conf']:
            upload(sftp, ROOT / 'deploy/windows' / name, 'C:/ETFQuantLab/staging/public-web/' + name)
        for name in ['install-public-web.ps1', 'activate-public-web.ps1',
                     'renew-public-web.ps1', 'issue-public-certificate.ps1']:
            upload(sftp, ROOT / 'deploy/windows' / name, 'C:/ETFQuantLab/staging/scripts/' + name)
        remote(client, "& 'C:\\ETFQuantLab\\staging\\scripts\\install-public-web.ps1'",
               'publish-web-install.log')
        if not args.install_only:
            remote(client, "& 'C:\\ETFQuantLab\\staging\\scripts\\issue-public-certificate.ps1'",
                   'publish-web-certificate.log')
    print('已完成Web入口部署步骤；公网访问和登录须继续实际验证。')


if __name__ == '__main__':
    main()
