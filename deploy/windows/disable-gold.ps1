param([string]$Root = 'C:\ETFQuantLab')
. "$PSScriptRoot\common.ps1"
Assert-EtfRoot $Root
# 首选局部回退：只关闭黄金新任务，保留ETF开关、原数据库、冻结研究和全部凭据。
$configuration = Get-Content -LiteralPath "$Root\config\runtime.json" -Raw | ConvertFrom-Json
$configuration | Add-Member -NotePropertyName ETF_GOLD_ENABLED -NotePropertyValue 'false' -Force
$configuration | Add-Member -NotePropertyName ETF_GOLD_LLM_ENABLED -NotePropertyValue 'false' -Force
Write-Utf8Json "$Root\config\runtime.json" $configuration
Restart-Service ETFQuantBackend
Wait-Health
Write-Output 'Gold scheduling disabled; ETF scheduling and all research records retained.'
