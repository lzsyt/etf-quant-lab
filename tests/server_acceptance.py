"""部署后真实行情、模型、预测冻结与模拟账户验收，不调用付费API或真实交易。"""
import hashlib
import json
from pathlib import Path
import sys
import time

from smoke import authenticated_client

ROOT = Path(__file__).resolve().parents[1]


def digest(value):
    """比较完整冻结记录，防止重复日终改写已有预测。"""
    return hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()


def main():
    """通过鉴权后的正式API验证云端日终流程与业务键幂等。"""
    with authenticated_client('http://127.0.0.1:18088') as client:
        before = client.get('/api/predictions').json()
        request = {'type':'EOD','payload':{},'business_key':'windows-final-acceptance-20261024'}
        first = client.post('/api/tasks',json=request)
        first.raise_for_status()
        second = client.post('/api/tasks',json=request)
        second.raise_for_status()
        assert first.json()['id'] == second.json()['id']
        identifier = first.json()['id']
        for _ in range(160):
            task = client.get('/api/tasks/'+identifier).json()
            if task['status'] == 'SUCCEEDED':
                break
            if task['status'] == 'FAILED':
                raise AssertionError('Cloud EOD failed; inspect task '+identifier)
            time.sleep(3)
        else:
            raise AssertionError('Cloud EOD timed out')
        after = client.get('/api/predictions').json()
        assert digest(before) == digest(after)
        report = task['result']
        assert report['predictions']['model_failures'] == []
        assert all(row.get('count',0) > 0 and row.get('nature') == 'REAL' for row in report['market'])
        system = client.get('/api/system').json()
        accounts = client.get('/api/paper/accounts').json()
        models = client.get('/api/models').json()
        assert len(accounts) == 11 and all(row['enabled'] for row in accounts)
        assert len(models) >= 12
        assert system['quant'] == 'UP' and system['scheduling_enabled']
        real = [row for row in system['market'] if row['nature']=='REAL' and row['price_basis']=='RAW']
        result = {'status':'PASSED','task_id':identifier,'real_bars':sum(row['count'] for row in real),
            'coverage':real,'models':len(models),'paper_accounts':len(accounts),'predictions':len(after),
            'prediction_digest_unchanged':True,'task_idempotency':True,
            'daily':{key:value for key,value in report.items() if key!='calendar'},
            'calendar_days':len(report.get('calendar',{}).get('dates',[])),
            'llm':system['llm'],'cost_total_usd':system['cost_total_usd'],
            'jvm_max_mb':system['jvm_max_mb'],'scheduling_enabled':system['scheduling_enabled']}
        (ROOT/'.runtime/evidence/server-final-api.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
        print(json.dumps({key:result[key] for key in ['status','real_bars','models','paper_accounts','predictions',
            'prediction_digest_unchanged','task_idempotency','cost_total_usd']},ensure_ascii=False))


if __name__ == '__main__':
    main()
