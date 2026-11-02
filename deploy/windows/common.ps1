Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$global:ProgressPreference = 'SilentlyContinue'

function Assert-EtfRoot([string]$Root) {
    # 所有写操作限定到专属安装目录，禁止路径参数误指向其他项目。
    if ([IO.Path]::GetFullPath($Root).TrimEnd('\') -ne 'C:\ETFQuantLab') {
        throw 'Only C:\ETFQuantLab is supported by this deployment.'
    }
}

function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Command failed: $([IO.Path]::GetFileName($Program))" }
}

function Write-Utf8Json([string]$Path, $Value) {
    $temporary = "$Path.tmp"
    [IO.File]::WriteAllText($temporary, ($Value | ConvertTo-Json -Depth 30), [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporary -Destination $Path -Force
}

function Read-Environment([string]$Root) {
    $configuration = Get-Content -LiteralPath "$Root\config\runtime.json" -Raw | ConvertFrom-Json
    foreach ($entry in $configuration.PSObject.Properties) {
        [Environment]::SetEnvironmentVariable($entry.Name, [string]$entry.Value, 'Process')
    }
}

function Pause-GoldTasksForLegacy([string]$Root) {
    # 仅在黄金版本切回旧应用时调用：旧worker不认识GOLD类型，暂停这些任务而保留ETF日终。
    # 不恢复数据库、不删除研究记录；黄金任务保留原request、result、attempts供新版人工恢复。
    Assert-EtfRoot $Root
    Read-Environment $Root
    $env:PGPASSWORD = $env:ETF_DB_PASSWORD
    try {
        $sql = "UPDATE task_execution SET status='PAUSED',updated_at=now() WHERE left(task_type,5)='GOLD_' AND status IN ('QUEUED','RUNNING','FAILED');"
        & "$Root\runtime\pgsql\bin\psql.exe" -h 127.0.0.1 -p 55432 -U etf_app -d etf_quant_lab -X -q -v ON_ERROR_STOP=1 -c $sql
        if ($LASTEXITCODE -ne 0) { throw 'Cannot pause gold-only queue before legacy application rollback.' }
    } finally { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
}

function Pause-FundTasksForLegacy([string]$Root) {
    # 旧版worker不识别FUND任务；回退前暂停待处理任务，保留请求、结果和账本证据。
    Assert-EtfRoot $Root
    Read-Environment $Root
    $env:PGPASSWORD = $env:ETF_DB_PASSWORD
    try {
        $sql = "UPDATE task_execution SET status='PAUSED',updated_at=now() WHERE left(task_type,5)='FUND_' AND status IN ('QUEUED','RUNNING','FAILED','WAITING_DATA');"
        & "$Root\runtime\pgsql\bin\psql.exe" -h 127.0.0.1 -p 55432 -U etf_app -d etf_quant_lab -X -q -v ON_ERROR_STOP=1 -c $sql
        if ($LASTEXITCODE -ne 0) { throw 'Cannot pause fund-only queue before legacy application rollback.' }
    } finally { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
}

function Pause-ForecastTasksForLegacy([string]$Root) {
    # 必须先停止新版worker：旧版不能识别统一预测任务，连同等待数据和可重试任务一起暂停。
    # 先持久化原状态再改队列；记录只含恢复所需的身份和状态，不包含请求正文、配置或凭证。
    Assert-EtfRoot $Root
    Read-Environment $Root
    $env:PGPASSWORD = $env:ETF_DB_PASSWORD
    try {
        $query = @"
SELECT coalesce(jsonb_agg(jsonb_build_object(
    'id',id,'task_type',task_type,'status',status,'updated_at',updated_at
) ORDER BY id),'[]'::jsonb)
FROM task_execution
WHERE task_type IN ('FORECAST_RUN','FORECAST_EVALUATE','FORECAST_PREPARE')
  AND status IN ('QUEUED','RUNNING','FAILED','WAITING_DATA');
"@
        $raw = & "$Root\runtime\pgsql\bin\psql.exe" -h 127.0.0.1 -p 55432 -U etf_app -d etf_quant_lab -X -q -t -A -w -v ON_ERROR_STOP=1 -c $query
        if ($LASTEXITCODE -ne 0) { throw 'Cannot read forecast queue before legacy application rollback.' }
        $tasks = ConvertFrom-Json -InputObject ($raw -join [Environment]::NewLine)
        if ($null -eq $tasks) { return }
        $tasks = @($tasks)
        if ($tasks.Count -eq 0) { return }

        # 独立目录不覆盖上一次回退证据；ACL仅允许管理员、SYSTEM和专用部署账号读取。
        $destination = "$Root\backup\forecast-pause-$([Guid]::NewGuid().ToString('N'))"
        New-Item -ItemType Directory -Path $destination | Out-Null
        Invoke-Checked 'icacls.exe' @($destination, '/inheritance:r', '/grant:r', '*S-1-5-18:(OI)(CI)F', '*S-1-5-32-544:(OI)(CI)F')
        $deployUser = Get-LocalUser etfdeploy -ErrorAction SilentlyContinue
        if ($deployUser) { Invoke-Checked 'icacls.exe' @($destination, '/grant', "*$($deployUser.SID.Value):(OI)(CI)M") }
        Write-Utf8Json "$destination\task-state.json" @{
            reason='FORECAST_V17_WITH_LEGACY_APPLICATION';
            created_at=(Get-Date).ToUniversalTime().ToString('o');
            tasks=$tasks
        }

        # UUID来自已保存的数据库结果；只暂停这些任务，不扫入快照之外的新任务或修改业务结果。
        $ids = ($tasks | ForEach-Object { "'$([Guid]$_.id)'::uuid" }) -join ','
        $sql = "UPDATE task_execution SET status='PAUSED',updated_at=now() WHERE id IN ($ids) AND status IN ('QUEUED','RUNNING','FAILED','WAITING_DATA');"
        # 任务较多时经标准输入传SQL，避免Windows命令行长度上限截断UUID清单。
        $sql | & "$Root\runtime\pgsql\bin\psql.exe" -h 127.0.0.1 -p 55432 -U etf_app -d etf_quant_lab -X -q -w -v ON_ERROR_STOP=1 -f -
        if ($LASTEXITCODE -ne 0) { throw 'Cannot pause forecast queue before legacy application rollback.' }
        Write-Output "Forecast queue paused; original task states saved: $destination\task-state.json"
    } finally { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
}

function Wait-QuantReady([int]$Seconds = 120) {
    # Windows服务显示Running时子进程可能仍在导入模型库；等真正健康后才启动任务工作线程。
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        try {
            $python = Invoke-RestMethod 'http://127.0.0.1:18081/health' -TimeoutSec 4
            if ($python.status -eq 'UP') { return }
        } catch { }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw 'Quant engine startup readiness timed out.'
}

function Wait-Health([int]$Seconds = 150) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        try {
            $java = Invoke-RestMethod 'http://127.0.0.1:18080/actuator/health' -TimeoutSec 4
            $python = Invoke-RestMethod 'http://127.0.0.1:18081/health' -TimeoutSec 4
            if ($java.status -eq 'UP' -and $python.status -eq 'UP') { return }
        } catch { }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw 'Application health check timed out.'
}
