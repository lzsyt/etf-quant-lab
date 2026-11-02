#!/usr/bin/env python3
"""专属Windows服务器首次初始化；已有数据库和配置绝不自动覆盖。"""
import argparse
import crypt
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import zipfile

from publish import ROOT, RUNTIME, connect, mkdirs, remote, upload


def protected_json(path, value):
    """机密只写当前用户可读文件，不打印、不进入Git或命令日志。"""
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w') as handle:
        json.dump(value, handle)


def prepare_configuration():
    """新服务器使用独立随机凭据；重复引导复用原配置，避免误改密码。"""
    path = RUNTIME/'server-secrets.json'
    if not path.exists():
        password = secrets.token_urlsafe(28)
        protected_json(path, {'admin_password': password,
            'admin_hash': crypt.crypt(password, crypt.mksalt(crypt.METHOD_BLOWFISH)),
            'db_password': secrets.token_urlsafe(36), 'pg_owner_password': secrets.token_urlsafe(36),
            'internal_token': secrets.token_urlsafe(40)})
    config = json.loads(path.read_text())
    environment = {'ETF_DB_URL':'jdbc:postgresql://127.0.0.1:55432/etf_quant_lab', 'ETF_DB_USER':'etf_app',
        'ETF_DB_PASSWORD':config['db_password'], 'ETF_ADMIN_PASSWORD_HASH':config['admin_hash'],
        'ETF_INTERNAL_TOKEN':config['internal_token'], 'ETF_BIND_ADDRESS':'127.0.0.1',
        'ETF_PORT':'18080', 'ETF_QUANT_URL':'http://127.0.0.1:18081', 'ETF_SCHEDULING_ENABLED':'true',
        'ETF_LLM_ENABLED':'false', 'ETF_LLM_DAILY_USD':'0'}
    for name, value in [('runtime.json', environment), ('bootstrap.json', {'pg_owner_password':config['pg_owner_password']})]:
        target = RUNTIME/('server-'+name)
        if not target.exists():
            protected_json(target, value)


def seed_database():
    """一致性导出完整研究库，测试数据库完全独立，不会混入验收测试记录。"""
    target = RUNTIME/'seed-research.dump'
    config = json.loads((RUNTIME/'secrets.json').read_text())
    binary = ROOT/'.tools/postgres/usr/lib/postgresql/16/bin/pg_dump'
    command = [str(binary) if binary.exists() else shutil.which('pg_dump'), '-h','127.0.0.1','-p','55432',
               '-U','etf_app','-d','etf_quant_lab','-Fc','--no-owner','--no-acl','-f',str(target)]
    subprocess.run(command,env=dict(os.environ,PGPASSWORD=config['db_password']),check=True)
    os.chmod(target,0o600)
    return target


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--host', default='example.invalid')
    parser.add_argument('--user', default='Administrator')
    parser.add_argument('--prepare-only', action='store_true')
    parser.add_argument('--seed-only', action='store_true', help='跳过已验证的运行时安装，再检查空库导入与缺失模型')
    args = parser.parse_args()
    prepare_configuration()
    with connect(args.host,args.user) as client, client.open_sftp() as sftp:
        mkdirs(sftp,'C:/ETFQuantLab/staging/scripts')
        for path in (ROOT/'deploy/windows').glob('*.ps1'):
            upload(sftp,path,'C:/ETFQuantLab/staging/scripts/'+path.name)
        parser_script = r"""
$errorsFound = @()
Get-ChildItem 'C:\ETFQuantLab\staging\scripts' -Filter '*.ps1' | ForEach-Object {
  $tokens = $null; $errors = $null
  [System.Management.Automation.Language.Parser]::ParseFile($_.FullName,[ref]$tokens,[ref]$errors) | Out-Null
  foreach ($parseIssue in $errors) { $errorsFound += ($_.Name + ': ' + $parseIssue.Message) }
}
if ($errorsFound.Count) { $errorsFound | Write-Output; throw 'PowerShell syntax validation failed.' }
& 'C:\ETFQuantLab\staging\scripts\bootstrap-server.ps1' -PrepareOnly
"""
        remote(client,parser_script,'bootstrap-prepare.log')
        print('Protected server directories and PowerShell syntax verified.',flush=True)
        if args.prepare_only:
            return
        exists = remote(client,"Test-Path 'C:\\ETFQuantLab\\config\\runtime.json'",'bootstrap-config-check.log').strip()
        if exists.lower() != 'true':
            for name in ['runtime.json','bootstrap.json']:
                upload(sftp,RUNTIME/('server-'+name),'C:/ETFQuantLab/config/'+name)
        manifest = json.loads((ROOT/'deploy/windows/artifacts.json').read_text())
        for name, artifact in manifest.items():
            destination = 'C:/ETFQuantLab/staging/dependencies/'+artifact['filename']
            try:
                complete = sftp.stat(destination).st_size == artifact['size']
            except OSError:
                complete = False
            if not complete:
                upload(sftp,ROOT/'.tools/downloads'/artifact['filename'],destination)
            print(name+' runtime uploaded',flush=True)
        upload(sftp,ROOT/'deploy/windows/artifacts.json','C:/ETFQuantLab/staging/dependencies/artifacts.json')
        # Windows专用依赖锁定于源码，不依赖开发机临时freeze文件。
        lock = ROOT/'deploy/windows/requirements-windows.lock'
        upload(sftp,lock,'C:/ETFQuantLab/staging/dependencies/requirements-windows.lock')
        for wheel in sorted((ROOT/'.tools/wheels-win').glob('*.whl')):
            destination='C:/ETFQuantLab/staging/dependencies/wheels/'+wheel.name
            try:
                complete=sftp.stat(destination).st_size==wheel.stat().st_size
            except OSError:
                complete=False
            if not complete:
                upload(sftp,wheel,destination)
        print('Pinned Windows Python wheels uploaded.',flush=True)
        key=Path(os.environ.get('ETF_DEPLOY_SSH_KEY',str(Path.home()/'.ssh/etf_quant_lab_ed25519'))+'.pub')
        upload(sftp,key,'C:/ETFQuantLab/staging/deploy.pub')
        if not args.seed_only:
            remote(client,r"& 'C:\ETFQuantLab\staging\scripts\bootstrap-server.ps1' -PublicKeyPath 'C:\ETFQuantLab\staging\deploy.pub'",'bootstrap-install.log')
        print('Native runtime, isolated database and deploy account initialized.',flush=True)
        dump = seed_database()
        upload(sftp,dump,'C:/ETFQuantLab/staging/seed-research.dump')
        seed_script = r"""
. 'C:\ETFQuantLab\staging\scripts\common.ps1'
Read-Environment 'C:\ETFQuantLab'
$env:PGPASSWORD=$env:ETF_DB_PASSWORD
try {
 $count=& 'C:\ETFQuantLab\runtime\pgsql\bin\psql.exe' -h 127.0.0.1 -p 55432 -U etf_app -d etf_quant_lab -At -c "select count(*) from information_schema.tables where table_schema='public'"
 if ($LASTEXITCODE -ne 0) {throw 'Seed preflight failed.'}
 if ($count -eq '0') {
  Invoke-Checked 'C:\ETFQuantLab\runtime\pgsql\bin\pg_restore.exe' @('-h','127.0.0.1','-p','55432','-U','etf_app','-d','etf_quant_lab','--no-owner','--no-acl','--exit-on-error','--single-transaction','C:\ETFQuantLab\staging\seed-research.dump')
  Write-Output 'Research data restored to previously empty database.'
 } else { Write-Output 'Existing database preserved; seed import skipped.' }
} finally { Remove-Item Env:PGPASSWORD }
"""
        remote(client,seed_script,'bootstrap-seed.log')
        for path in (RUNTIME/'models').iterdir():
            if path.is_file() and not path.name.endswith('.tmp'):
                destination='C:/ETFQuantLab/models/'+path.name
                try:
                    sftp.stat(destination)
                except OSError:
                    upload(sftp,path,destination)
        print('Research seed and immutable model artifacts ready. Run publish.py to start services.',flush=True)


if __name__ == '__main__':
    main()
