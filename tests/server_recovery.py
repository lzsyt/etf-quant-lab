"""已授权Windows环境的真实恢复验收；必须显式选择重启应用或整机重启。"""
import argparse
import json
from pathlib import Path
import sys
import time
import uuid

import paramiko

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'deploy/scripts'))
from publish import connect, remote  # noqa: E402


def sql(client, statement, label):
    """数据库密码仅注入远端子进程；只输出本次验收的统计值。"""
    script = r"""
. 'C:\ETFQuantLab\staging\scripts\common.ps1'
Read-Environment 'C:\ETFQuantLab'
$env:PGPASSWORD=$env:ETF_DB_PASSWORD
$sql=@'
""" + statement + r"""
'@
try {
 $sql | & 'C:\ETFQuantLab\runtime\pgsql\bin\psql.exe' -h 127.0.0.1 -p 55432 -U etf_app -d etf_quant_lab -At -v ON_ERROR_STOP=1
 if ($LASTEXITCODE -ne 0) {throw 'Acceptance SQL failed'}
} finally {Remove-Item Env:PGPASSWORD}
"""
    return remote(client, script, label + '.log').strip()


def snapshot(client):
    """冻结预测、账户资金及订单必须在恢复前后保持完全一致。"""
    query = """SELECT json_build_object(
 'predictions',(select count(*) from prediction),
 'prediction_digest',(select md5(string_agg(row_to_json(p)::text,'' order by id)) from prediction p),
 'accounts_digest',(select md5(string_agg(row_to_json(a)::text,'' order by id)) from paper_account a),
 'orders',(select count(*) from paper_order), 'fills',(select count(*) from paper_fill),
 'snapshots',(select count(*) from account_snapshot));
"""
    return json.loads(sql(client, query, 'recovery-snapshot'))


def main():
    """停止应用后加入一项真实回测任务，验证无人登录自动恢复与持久队列续跑。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('--mode', choices=['restart', 'reboot'], required=True)
    parser.add_argument('--host', default='example.invalid')
    args = parser.parse_args()
    task_id = str(uuid.uuid4())
    boot_command = '(Get-CimInstance Win32_OperatingSystem).LastBootUpTime.ToUniversalTime().ToString(\'o\')'
    with connect(args.host, 'Administrator') as client:
        before = snapshot(client)
        boot_before = remote(client, boot_command, 'recovery-boot-before.log').strip()
        remote(client, r"& 'C:\ETFQuantLab\staging\scripts\stop.ps1'", 'recovery-stop.log')
        payload = json.dumps({'symbols':['510300','510500','159915'],
                              'strategies':['ma','lightgbm','xgboost','buy_hold','cash'],
                              'start':'2026-07-01','end':'2026-10-23','nature':'REAL',
                              'initial_capital':'100000.00'})
        sql(client, "insert into task_execution(id,business_key,task_type,status,request) values "
            f"('{task_id}','BACKTEST:recovery-{task_id}','BACKTEST','QUEUED','{payload}'::jsonb);",
            'recovery-enqueue')
        if args.mode == 'reboot':
            remote(client, 'shutdown.exe /r /t 5 /d p:4:1 /c "ETF Quant Lab authorized recovery acceptance"',
                   'recovery-reboot-request.log')
        else:
            remote(client, r"& 'C:\ETFQuantLab\staging\scripts\start.ps1'", 'recovery-start.log')
    started = time.monotonic()
    while time.monotonic() - started < 420:
        try:
            with connect(args.host, 'Administrator') as client:
                boot_after = remote(client, boot_command, 'recovery-boot-after.log').strip()
                if args.mode == 'reboot' and boot_after == boot_before:
                    time.sleep(5)
                    continue
                health = json.loads(remote(client,
                    r"& 'C:\ETFQuantLab\staging\scripts\health-check.ps1'", 'recovery-health.log'))
                task = json.loads(sql(client, "select row_to_json(t) from (select status,attempts,"
                    f"result->>'id' as experiment_id from task_execution where id='{task_id}') t;",
                    'recovery-task'))
                if task['status'] != 'SUCCEEDED':
                    if task['status'] == 'FAILED' and task['attempts'] >= 3:
                        raise AssertionError('Restored task failed')
                    time.sleep(3)
                    continue
                after = snapshot(client)
                assert before == after, 'Frozen research or account state changed during recovery'
                result = {'status':'PASSED','mode':args.mode,'boot_before':boot_before,
                    'boot_after':boot_after,'state_before':before,'state_after':after,
                    'queued_task':task_id,'task':task,'health':health,
                    'elapsed_seconds':round(time.monotonic()-started,1)}
                path = ROOT / ('.runtime/evidence/server-' + args.mode + '.json')
                path.write_text(json.dumps(result,ensure_ascii=False,indent=2))
                print(json.dumps({'status':'PASSED','mode':args.mode,'state_unchanged':True,
                    'queued_task_recovered':True,'elapsed_seconds':result['elapsed_seconds']}))
                return
        except (OSError, RuntimeError, EOFError, paramiko.SSHException):
            # 整机启动期间连接失败属于预期，成功前不报告健康或跳过检查。
            pass
        time.sleep(5)
    raise RuntimeError('Windows recovery timed out; inspect protected logs and server services')


if __name__ == '__main__':
    main()
