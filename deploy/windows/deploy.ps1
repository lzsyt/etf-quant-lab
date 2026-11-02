param([string]$Root = 'C:\ETFQuantLab', [Parameter(Mandatory=$true)][string]$Package,
    [Parameter(Mandatory=$true)][string]$Sha256, [switch]$SkipBackup)
. "$PSScriptRoot\common.ps1"
Assert-EtfRoot $Root
if ((Get-FileHash $Package -Algorithm SHA256).Hash.ToLower() -ne $Sha256.ToLower()) { throw 'Release checksum failed.' }
$id = [IO.Path]::GetFileNameWithoutExtension($Package)
if ($id -notmatch '^[A-Za-z0-9._-]+$') { throw 'Invalid release identifier.' }
$folder = "$Root\releases\$id"
if (Test-Path $folder) { throw 'Release directories are immutable; choose a new release identifier.' }
$previous = $null
if (Test-Path "$Root\app\current.json") { $previous = Get-Content "$Root\app\current.json" -Raw | ConvertFrom-Json }
if (-not $SkipBackup) { & "$PSScriptRoot\backup.ps1" -Root $Root }
Expand-Archive $Package $folder
if (-not (Test-Path "$folder\backend.jar")) { throw 'Incomplete release package.' }
foreach ($name in @('common.ps1','run-service.ps1')) { Copy-Item "$PSScriptRoot\$name" "$Root\app\$name" -Force }
foreach ($component in @('quant','backend')) {
    $serviceId = if ($component -eq 'quant') { 'ETFQuantEngine' } else { 'ETFQuantBackend' }
    if (-not (Get-Service $serviceId -ErrorAction SilentlyContinue)) {
        $executable = "$Root\app\$serviceId.exe"
        Copy-Item "$Root\runtime\WinSW.exe" $executable
        $depend = if ($component -eq 'backend') { '<depend>ETFPostgreSQL</depend><depend>ETFQuantEngine</depend>' } else { '<depend>ETFPostgreSQL</depend>' }
        @"
<service>
  <id>$serviceId</id><name>$serviceId</name><description>ETF Quant Lab - paper trading only</description>
  <executable>C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe</executable>
  <arguments>-NoProfile -NonInteractive -File "$Root\app\run-service.ps1" -Component $component -Root "$Root"</arguments>
  <workingdirectory>$Root\app</workingdirectory><startmode>Automatic</startmode>
  <serviceaccount><domain>NT AUTHORITY</domain><user>LocalService</user><allowservicelogon>true</allowservicelogon></serviceaccount>
  $depend
  <onfailure action="restart" delay="10 sec"/><resetfailure>1 hour</resetfailure><stoptimeout>30 sec</stoptimeout>
  <logpath>$Root\logs</logpath><log mode="roll-by-size"><sizeThreshold>10240</sizeThreshold><keepFiles>8</keepFiles></log>
</service>
"@ | Set-Content "$Root\app\$serviceId.xml" -Encoding UTF8
        Invoke-Checked $executable @('install')
    }
    $identity = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
    if ($identity.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        $deployUser = Get-LocalUser etfdeploy -ErrorAction SilentlyContinue
        if ($deployUser) {
            $descriptor = (& sc.exe sdshow $serviceId | Where-Object { $_ -match '^D:' }) -join ''
            $ace = "(A;;CCLCSWRPWPDTLOCRRC;;;$($deployUser.SID.Value))"
            if ($descriptor -notmatch [regex]::Escape($deployUser.SID.Value)) {
                $descriptor = $descriptor.Replace('S:', "$ace`S:")
                if ($descriptor -notmatch [regex]::Escape($ace)) { $descriptor += $ace }
                Invoke-Checked 'sc.exe' @('sdset', $serviceId, $descriptor)
            }
        }
    }
}
try {
    & "$PSScriptRoot\stop.ps1" -Root $Root
    Write-Utf8Json "$Root\app\current.json" @{id=$id}
    # 新版具备交易日门禁，重新发布时才解除故障回退的调度暂停标记。
    if (Test-Path "$folder\database\migrations\V7__v11_integrity.sql") {
        if (Test-Path "$Root\app\recovery-no-schedule.json") {
            Move-Item "$Root\app\recovery-no-schedule.json" "$folder\prior-recovery-state.json"
        }
    }
    & "$PSScriptRoot\start.ps1" -Root $Root
    & "$PSScriptRoot\health-check.ps1" -Root $Root
    if ($previous) { Write-Utf8Json "$Root\app\previous.json" $previous }
    Write-Utf8Json "$folder\deployment-result.json" @{status='HEALTHY';release=$id;at=(Get-Date).ToUniversalTime().ToString('o')}
    Write-Output "Release healthy: $id"
} catch {
    $failure = $_.Exception.GetType().Name
    & "$PSScriptRoot\stop.ps1" -Root $Root
    if ($previous) {
        # 旧worker不识别V17统一预测任务；先留存原状态并暂停，再启动回退应用。
        if ((Test-Path "$folder\database\migrations\V17__unified_forecast_runs.sql") -and
            -not (Test-Path "$Root\releases\$($previous.id)\database\migrations\V17__unified_forecast_runs.sql")) {
            Pause-ForecastTasksForLegacy $Root
        }
        if ((Test-Path "$folder\database\migrations\V15__fund_research.sql") -and
            -not (Test-Path "$Root\releases\$($previous.id)\database\migrations\V15__fund_research.sql")) {
            Pause-FundTasksForLegacy $Root
        }
        if ((Test-Path "$folder\database\migrations\V12__physical_gold_research.sql") -and
            -not (Test-Path "$Root\releases\$($previous.id)\database\migrations\V12__physical_gold_research.sql")) {
            Pause-GoldTasksForLegacy $Root
        }
        $credentialMigration = 'database\migrations\V6__admin_credentials.sql'
        if ((Test-Path "$folder\$credentialMigration") -and
            -not (Test-Path "$Root\releases\$($previous.id)\$credentialMigration")) {
            throw 'Automatic rollback refused: previous application cannot enforce persisted credentials.'
        }
        # 不降级数据库，不使旧调度器忽略新增is_open状态；恢复后等待兼容新版发布。
        if ((Test-Path "$folder\database\migrations\V10__forward_open_protocol.sql") -and
            -not (Test-Path "$Root\releases\$($previous.id)\database\migrations\V10__forward_open_protocol.sql")) {
            Write-Utf8Json "$Root\app\recovery-no-schedule.json" @{reason='FORWARD_V2_WITH_LEGACY_APPLICATION';created_at=(Get-Date).ToUniversalTime().ToString('o')}
        }
        if ((Test-Path "$folder\database\migrations\V7__v11_integrity.sql") -and
            -not (Test-Path "$Root\releases\$($previous.id)\database\migrations\V7__v11_integrity.sql")) {
            Write-Utf8Json "$Root\app\recovery-no-schedule.json" @{reason='V11_SCHEMA_WITH_LEGACY_APPLICATION';created_at=(Get-Date).ToUniversalTime().ToString('o')}
        }
        Write-Utf8Json "$Root\app\current.json" $previous
        & "$PSScriptRoot\start.ps1" -Root $Root
        Write-Output "Previous application restored: $($previous.id)"
    }
    throw "Deployment failed ($failure). Database retained; no automatic schema downgrade."
}
