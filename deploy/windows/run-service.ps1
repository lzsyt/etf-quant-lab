param([ValidateSet('backend','quant')][string]$Component, [string]$Root = 'C:\ETFQuantLab')
. "$PSScriptRoot\common.ps1"
Assert-EtfRoot $Root
Read-Environment $Root
$release = Get-Content "$Root\app\current.json" -Raw | ConvertFrom-Json
if ($release.id -notmatch '^[A-Za-z0-9._-]+$') { throw 'Invalid release identifier.' }
$folder = "$Root\releases\$($release.id)"
$metadata = Get-Content "$folder\release.json" -Raw | ConvertFrom-Json
$env:ETF_CODE_VERSION = $metadata.commit
$env:PYTHONUTF8 = '1'
$env:OMP_NUM_THREADS = '1'
$env:OPENBLAS_NUM_THREADS = '1'
$env:MKL_NUM_THREADS = '1'
$env:ETF_MODEL_DIR = "$Root\models"
$env:ETF_LOG_DIR = "$Root\logs"
$env:PYTHONPATH = "$folder\quant-engine\src;$folder\ai-analysis-service\src"
Set-Location "$Root\app"
if ($Component -eq 'backend') {
    # 回退旧应用时保留网站读取能力，暂停旧版不理解新交易日语义的自动任务。
    if (Test-Path "$Root\app\recovery-no-schedule.json") {
        $env:ETF_SCHEDULING_ENABLED = 'false'
        $env:ETF_FORWARD_ENABLED = 'false'
        $env:ETF_FORWARD_LLM_ENABLED = 'false'
        # 旧队列worker和日终入队开关彼此独立，同时阻止旧代码消费积压任务。
        $env:ETF_WORKER_DELAY = '315360000000'
    }
    Wait-QuantReady
    $arguments = @('-Xms128m', '-Xmx768m', '-XX:ActiveProcessorCount=2',
        '-Dfile.encoding=UTF-8', '-Duser.timezone=UTC', '-jar', "$folder\backend.jar")
    & "$Root\runtime\java\bin\java.exe" @arguments
} else {
    $arguments = @('-m', 'uvicorn', 'etf_quant.api:app', '--host', '127.0.0.1', '--port', '18081', '--workers', '1')
    & "$Root\runtime\venv-v1\Scripts\python.exe" @arguments
}
exit $LASTEXITCODE
