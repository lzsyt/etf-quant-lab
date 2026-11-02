param([string]$Root = 'C:\ETFQuantLab', [string]$ReleaseId)
. "$PSScriptRoot\common.ps1"
Assert-EtfRoot $Root
if (-not $ReleaseId) {
    if (-not (Test-Path "$Root\app\previous.json")) { throw 'No previous healthy application release.' }
    $ReleaseId = (Get-Content "$Root\app\previous.json" -Raw | ConvertFrom-Json).id
}
if ($ReleaseId -notmatch '^[A-Za-z0-9._-]+$' -or -not (Test-Path "$Root\releases\$ReleaseId\backend.jar")) {
    throw 'Rollback release is invalid or unavailable.'
}
# 网页改密已切换到数据库存储，旧版会忽略新哈希并重新启用初始化密码，必须拒绝这种回退。
$current = (Get-Content "$Root\app\current.json" -Raw | ConvertFrom-Json).id
$credentialMigration = 'database\migrations\V6__admin_credentials.sql'
if ((Test-Path "$Root\releases\$current\$credentialMigration") -and
    -not (Test-Path "$Root\releases\$ReleaseId\$credentialMigration")) {
    throw 'Rollback target does not support persisted administrator passwords. Choose a compatible release.'
}
# 不执行数据库降级；只允许兼容当前Flyway版本的应用回滚，结构恢复另行审核。
if ((Test-Path "$Root\releases\$current\database\migrations\V10__forward_open_protocol.sql") -and
    -not (Test-Path "$Root\releases\$ReleaseId\database\migrations\V10__forward_open_protocol.sql")) {
    Write-Utf8Json "$Root\app\recovery-no-schedule.json" @{reason='FORWARD_V2_WITH_LEGACY_APPLICATION';created_at=(Get-Date).ToUniversalTime().ToString('o')}
}
if ((Test-Path "$Root\releases\$current\database\migrations\V7__v11_integrity.sql") -and
    -not (Test-Path "$Root\releases\$ReleaseId\database\migrations\V7__v11_integrity.sql")) {
    Write-Utf8Json "$Root\app\recovery-no-schedule.json" @{reason='V11_SCHEMA_WITH_LEGACY_APPLICATION';created_at=(Get-Date).ToUniversalTime().ToString('o')}
}
& "$PSScriptRoot\stop.ps1" -Root $Root
# 新版服务已停止，原状态持久化并暂停成功后才允许旧版开始消费队列。
if ((Test-Path "$Root\releases\$current\database\migrations\V17__unified_forecast_runs.sql") -and
    -not (Test-Path "$Root\releases\$ReleaseId\database\migrations\V17__unified_forecast_runs.sql")) {
    Pause-ForecastTasksForLegacy $Root
}
if ((Test-Path "$Root\releases\$current\database\migrations\V15__fund_research.sql") -and
    -not (Test-Path "$Root\releases\$ReleaseId\database\migrations\V15__fund_research.sql")) {
    Pause-FundTasksForLegacy $Root
}
if ((Test-Path "$Root\releases\$current\database\migrations\V12__physical_gold_research.sql") -and
    -not (Test-Path "$Root\releases\$ReleaseId\database\migrations\V12__physical_gold_research.sql")) {
    Pause-GoldTasksForLegacy $Root
}
Write-Utf8Json "$Root\app\current.json" @{id=$ReleaseId}
& "$PSScriptRoot\start.ps1" -Root $Root
& "$PSScriptRoot\health-check.ps1" -Root $Root
Write-Output "Application rollback healthy: $ReleaseId. Database was not reverted."
