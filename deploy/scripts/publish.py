#!/usr/bin/env python3
"""从WSL构建、校验、SFTP上传并发布；SSH只使用专属密钥与已固定主机密钥。"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import zipfile

import paramiko

ROOT = Path(__file__).resolve().parents[2]
RUNTIME = ROOT / '.runtime'


def connect(host, user):
    """拒绝未知主机密钥，凭据不进入命令参数、源码或发布包。"""
    client = paramiko.SSHClient()
    client.load_host_keys(os.environ.get('ETF_DEPLOY_KNOWN_HOSTS', str(Path.home()/'.ssh/etf_quant_lab_known_hosts')))
    client.set_missing_host_key_policy(paramiko.RejectPolicy())
    client.connect(host, username=user,
                   key_filename=os.environ.get('ETF_DEPLOY_SSH_KEY', str(Path.home()/'.ssh/etf_quant_lab_ed25519')),
                   allow_agent=False, look_for_keys=False, timeout=15)
    return client


def remote(client, script, log_name, timeout=1800):
    """持续读取两个输出流，避免远端长任务被缓冲区阻塞；日志仅保存在受限运行目录。"""
    script = "[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false); $ErrorActionPreference='Stop'; $global:ProgressPreference='SilentlyContinue';\n" + script
    encoded = base64.b64encode(script.encode('utf-16-le')).decode()
    channel = client.get_transport().open_session()
    channel.set_combine_stderr(True)
    channel.exec_command('powershell.exe -NoLogo -NoProfile -NonInteractive -EncodedCommand '+encoded)
    deadline = time.monotonic()+timeout
    data = bytearray()
    with (RUNTIME/'logs'/log_name).open('wb') as log:
        while True:
            if channel.recv_ready():
                chunk = channel.recv(65536)
                data.extend(chunk)
                log.write(chunk)
                log.flush()
            elif channel.exit_status_ready():
                break
            elif time.monotonic()>deadline:
                channel.close()
                raise TimeoutError('Remote task timed out; inspect protected deployment log.')
            else:
                time.sleep(.2)
        code = channel.recv_exit_status()
    if code:
        raise RuntimeError(f'Remote command failed ({code}); see .runtime/logs/{log_name}')
    return data.decode('utf-8', errors='replace')


def mkdirs(sftp, path):
    """只创建本次项目路径；存在目录则原样复用。"""
    current = 'C:'
    for part in path.removeprefix('C:/').split('/'):
        current += '/'+part
        try:
            sftp.stat(current)
        except OSError:
            sftp.mkdir(current)


def upload(sftp, local, destination):
    """上传临时文件后再重命名，部署脚本还会核验SHA256。"""
    local = Path(local)
    mkdirs(sftp, destination.rsplit('/',1)[0])
    temporary = destination+'.uploading'
    if local.suffix == '.ps1':
        with sftp.open(temporary, 'wb') as handle:
            handle.write(local.read_text(encoding='utf-8-sig').encode('utf-8-sig'))
    else:
        sftp.put(str(local), temporary)
    try:
        sftp.stat(destination)
        sftp.remove(destination)
    except OSError:
        pass
    sftp.rename(temporary, destination)


def build(skip_tests=False):
    """本机构建所有发布产物，Windows服务器只安装已冻结的依赖与运行应用。"""
    if subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True).strip():
        raise RuntimeError('Commit the reviewed changes before building a traceable release.')
    environment = dict(os.environ)
    node = Path.home()/'.nvm/versions/node/v22.22.0/bin'
    if node.exists():
        environment['PATH'] = str(node)+os.pathsep+environment['PATH']
    commands = [(['npm','ci','--no-audit'], ROOT/'frontend'), (['npm','run','build'], ROOT/'frontend')]
    if not skip_tests:
        commands += [([str(ROOT/'.venv/bin/python'),'-m','pytest','quant-engine/tests','ai-analysis-service/tests','-q'],ROOT),
                     (['python3','deploy/scripts/local.py','test-java'],ROOT)]
    commands += [(['bash','deploy/scripts/mvn.sh','-DskipTests','package'], ROOT)]
    for index, (command, cwd) in enumerate(commands):
        with (RUNTIME/'logs'/f'publish-build-{index}.log').open('w') as log:
            subprocess.run(command, cwd=cwd, env=environment, stdout=log, stderr=subprocess.STDOUT, check=True)
    if subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True).strip():
        raise RuntimeError('Build or formatting changed tracked files; review and commit before publishing.')
    commit = subprocess.check_output(['git','rev-parse','--short','HEAD'], cwd=ROOT, text=True).strip()
    release = time.strftime('%Y%m%d-%H%M%S')+'-'+commit
    package = RUNTIME/'releases'/(release+'.zip')
    package.parent.mkdir(exist_ok=True)
    with zipfile.ZipFile(package,'w',zipfile.ZIP_DEFLATED) as archive:
        archive.write(ROOT/'backend/target/etf-quant-lab-1.0.0.jar','backend.jar')
        for folder in ['quant-engine/src','ai-analysis-service/src','database/migrations']:
            for path in (ROOT/folder).rglob('*'):
                if path.is_file() and '__pycache__' not in path.parts and not path.name.endswith('.pyc'):
                    archive.write(path,path.relative_to(ROOT))
        archive.writestr('release.json',json.dumps({'id':release,'commit':commit,'mode':'RESEARCH_ONLY_NO_EXECUTION',
            'version':'2.3.0','runtime':'venv-v1','schema_backward_compatible_to':11,
            'protocol_version':'OPEN_NET_REBALANCE_V1','gold_label_version':'SGE_CLOSE_RETURN_63_126_V1',
            'fund_target_version':'fund-nav-change-20-60-v1','fund_schema':16,
            'forecast_schema':17,'automatic_default_enabled':False,
            'fund_jobs_default_enabled':False,'fund_paid_analysis_default_enabled':False}))
    return package


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--host', default='example.invalid')
    parser.add_argument('--user', default='etfdeploy')
    parser.add_argument('--skip-tests', action='store_true')
    parser.add_argument('--package', type=Path)
    args=parser.parse_args()
    package=args.package or build(args.skip_tests)
    digest=hashlib.sha256(package.read_bytes()).hexdigest()
    with connect(args.host,args.user) as client, client.open_sftp() as sftp:
        for script in (ROOT/'deploy/windows').glob('*.ps1'):
            upload(sftp,script,'C:/ETFQuantLab/staging/scripts/'+script.name)
        # 先在实际Windows PowerShell解析全部脚本；语法错误时尚未停止任何运行服务。
        remote(client, r"""
$files=@(Get-ChildItem 'C:\ETFQuantLab\staging\scripts\*.ps1')
foreach($file in $files) {
    $tokens=$null; $errors=$null
    [void][Management.Automation.Language.Parser]::ParseFile($file.FullName,[ref]$tokens,[ref]$errors)
    if($errors.Count -gt 0){throw ('PowerShell syntax check failed: '+$file.Name)}
}
Write-Output ('PowerShell syntax checked: '+$files.Count)
""", 'publish-powershell-syntax.log', timeout=60)
        # 模型按版本只补充新工件，保留远端已有版本及上线后训练产生的文件。
        for model in (RUNTIME/'models').iterdir():
            if not model.is_file() or model.name.endswith('.tmp'):
                continue
            destination='C:/ETFQuantLab/models/'+model.name
            try:
                sftp.stat(destination)
                with sftp.open(destination, 'rb') as handle:
                    remote_digest = hashlib.sha256(handle.read()).digest()
                if remote_digest != hashlib.sha256(model.read_bytes()).digest():
                    raise RuntimeError('Immutable model content mismatch: '+model.name)
            except FileNotFoundError:
                upload(sftp,model,destination)
        destination='C:/ETFQuantLab/staging/'+package.name
        upload(sftp,package,destination)
        remote(client,f"& 'C:\\ETFQuantLab\\staging\\scripts\\deploy.ps1' -Package '{destination}' -Sha256 '{digest}'",
               'publish-'+package.stem+'.log')
    print(json.dumps({'status':'HEALTHY','release':package.stem,'sha256':digest}))


if __name__=='__main__':
    main()
