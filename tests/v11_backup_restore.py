"""把最新ETF生产备份恢复至全新本地隔离库，验证备份可用而不覆盖任何已有数据。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'deploy/scripts'))
from publish import connect, remote


def main():
    """只有显式execute才下载受保护备份；记录校验摘要，不归档口令与管理员认证表。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true')
    args = parser.parse_args()
    if not args.execute:
        print(json.dumps({'status': 'NOT_EXECUTED', 'target': 'new isolated local ETF database'}))
        return
    stamp = time.strftime('%Y%m%d_%H%M%S')
    protected = ROOT / '.runtime' / ('restore-v11-' + stamp)
    protected.mkdir(mode=0o700)
    with connect('example.invalid', 'etfdeploy') as client, client.open_sftp() as sftp:
        result = remote(client, r"""
$m=Get-ChildItem 'C:\ETFQuantLab\backup' -Filter backup-manifest.json -Recurse -File |
 Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
if(-not $m){throw 'Backup manifest unavailable'}
$v=Get-Content $m.FullName -Raw | ConvertFrom-Json
@{directory=$m.DirectoryName;manifest=$v} | ConvertTo-Json -Depth 6 -Compress
""", 'v11-restore-discovery.log', timeout=30)
        record = json.loads(result)
        dump = protected / 'database.dump'
        sftp.get(record['directory'].replace('\\', '/') + '/database.dump', str(dump))
        dump.chmod(0o600)
    digest = hashlib.sha256(dump.read_bytes()).hexdigest()
    assert digest.upper() == record['manifest']['database_sha256'].upper(), 'Backup checksum mismatch'
    database = 'etf_quant_lab_restore_' + stamp
    command = ['psql', '-X', '-v', 'ON_ERROR_STOP=1', '-h', str(ROOT / '.runtime/socket'),
               '-p', '55432', '-U', 'etf_owner', '-d']
    subprocess.run(command + ['postgres', '-c', 'CREATE DATABASE ' + database + ' OWNER etf_app'],
                   check=True, stdout=subprocess.DEVNULL)
    # 不使用--clean，不接触任何已有数据库；跨平台备份仅还原业务内容与既有迁移。
    with (protected / 'restore.log').open('w') as log:
        subprocess.run(['pg_restore', '--exit-on-error', '--no-owner', '--no-privileges', '--role=etf_app',
                        '-h', str(ROOT / '.runtime/socket'), '-p', '55432', '-U', 'etf_owner',
                        '-d', database, str(dump)], check=True, stdout=log, stderr=log)
    tables = ['market_bar', 'prediction', 'prediction_input', 'prediction_actual', 'paper_account',
              'paper_order', 'paper_fill', 'paper_position', 'account_snapshot', 'backtest_experiment', 'model_version']
    # 表名在代码中固定，未拼接任何远程不可信输入。
    query = 'select jsonb_build_object(' + ','.join("'%s',(select count(*) from %s)" % (t, t) for t in tables) + ');'
    counts = json.loads(subprocess.check_output(command + [database, '-tA', '-c', query], text=True))
    baseline = json.loads((Path.home() / '.codex/workspaces/etf-quant-lab/output/v11-20261024/baseline.json').read_text())['data']
    for table in ['prediction', 'paper_account', 'paper_order', 'paper_fill', 'paper_position', 'account_snapshot']:
        assert counts[table] == len(baseline[table]), 'Restored historical count differs: ' + table
    assert counts['market_bar'] >= len(baseline['market_bar'])
    evidence = {'status': 'PASSED', 'restored_database': database, 'source_backup': record['directory'],
                'dump_sha256': digest, 'counts': counts, 'existing_databases_overwritten': False,
                'credentials_included_in_evidence': False}
    output = Path.home() / '.codex/workspaces/etf-quant-lab/output/v11-20261024/backup-restore.json'
    output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2))
    print(json.dumps(evidence, ensure_ascii=False))


if __name__ == '__main__':
    main()
