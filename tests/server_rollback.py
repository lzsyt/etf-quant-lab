"""已授权发布验收：部署故意不可启动的JAR，验证自动恢复原版本且业务数据不变。"""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'deploy/scripts'))
from publish import connect, remote, upload  # noqa: E402
from server_recovery import snapshot  # noqa: E402


def main():
    """只更换测试发布包的应用字节，不修改数据库迁移或已有版本目录。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('--package', type=Path, required=True)
    parser.add_argument('--host', default='example.invalid')
    args = parser.parse_args()
    invalid = ROOT / ('.runtime/releases/failed-acceptance-' + time.strftime('%Y%m%d-%H%M%S') + '.zip')
    with zipfile.ZipFile(args.package) as source, zipfile.ZipFile(invalid,'w',zipfile.ZIP_DEFLATED) as target:
        for item in source.infolist():
            content = b'Intentional invalid JAR for authorized rollback acceptance' if item.filename == 'backend.jar' else source.read(item.filename)
            target.writestr(item.filename, content)
    digest = hashlib.sha256(invalid.read_bytes()).hexdigest()
    pointer_script = r"Get-Content 'C:\ETFQuantLab\app\current.json' -Raw"
    with connect(args.host, 'Administrator') as admin:
        before = snapshot(admin)
    with connect(args.host,'etfdeploy') as client, client.open_sftp() as sftp:
        previous = json.loads(remote(client,pointer_script,'rollback-pointer-before.log'))
        destination = 'C:/ETFQuantLab/staging/' + invalid.name
        upload(sftp, invalid, destination)
        rejected = False
        try:
            remote(client, f"& 'C:\\ETFQuantLab\\staging\\scripts\\deploy.ps1' -Package '{destination}' -Sha256 '{digest}'",
                   'rollback-failed-release.log')
        except RuntimeError:
            rejected = True
        assert rejected, 'Invalid application release was unexpectedly accepted'
        current = json.loads(remote(client,pointer_script,'rollback-pointer-after.log'))
        assert current == previous, 'Previous application pointer was not restored'
        health = json.loads(remote(client,r"& 'C:\ETFQuantLab\staging\scripts\health-check.ps1'",'rollback-health.log'))
    with connect(args.host,'Administrator') as admin:
        after = snapshot(admin)
    assert before == after, 'Business state changed during failed application release'
    result={'status':'PASSED','rejected_release':invalid.stem,'restored_release':current['id'],
            'before':before,'after':after,'health':health,'database_downgrade':False}
    (ROOT / '.runtime/evidence/server-rollback.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
    print(json.dumps({'status':'PASSED','previous_application_restored':True,'business_state_unchanged':True}))


if __name__ == '__main__':
    main()
