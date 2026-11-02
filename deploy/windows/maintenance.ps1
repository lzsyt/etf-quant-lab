param([string]$Root = 'C:\ETFQuantLab')
. "$PSScriptRoot\common.ps1"
Assert-EtfRoot $Root
& "$PSScriptRoot\backup.ps1" -Root $Root
# 只清理已轮转的旧日志，不删除数据库、研究、预测、订单、模型或备份。
$cutoff = (Get-Date).AddDays(-30)
Get-ChildItem "$Root\logs" -File -Recurse | Where-Object {
    $_.LastWriteTime -lt $cutoff -and ($_.Name -match '^postgresql-\d{4}-\d{2}-\d{2}\.log$' -or $_.Extension -eq '.gz')
} | Remove-Item -Force
& "$PSScriptRoot\health-check.ps1" -Root $Root
