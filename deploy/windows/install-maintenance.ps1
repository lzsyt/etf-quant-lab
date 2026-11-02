param([string]$Root = 'C:\ETFQuantLab')
. "$PSScriptRoot\common.ps1"
Assert-EtfRoot $Root
$identity = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $identity.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Maintenance registration requires Administrator.'
}
# SYSTEM计划任务只执行管理员可写脚本，不能引用部署账号可改的staging/app脚本。
$scripts = "$Root\runtime\admin-scripts"
New-Item -ItemType Directory -Force $scripts | Out-Null
Invoke-Checked 'icacls.exe' @($scripts, '/inheritance:r', '/grant:r',
    '*S-1-5-18:(OI)(CI)F', '*S-1-5-32-544:(OI)(CI)F')
foreach ($name in @('common.ps1','backup.ps1','health-check.ps1','maintenance.ps1')) {
    Copy-Item "$PSScriptRoot\$name" "$scripts\$name" -Force
}
$action = New-ScheduledTaskAction -Execute 'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe' `
    -Argument "-NoProfile -NonInteractive -File `"$scripts\maintenance.ps1`" -Root `"$Root`""
$trigger = New-ScheduledTaskTrigger -Daily -At '02:00'
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 30)
Register-ScheduledTask -TaskName 'ETFQuantLab-Maintenance' -Action $action -Trigger $trigger `
    -Principal $principal -Settings $settings -Description 'ETF daily backup and rotated log retention' -Force | Out-Null
Write-Output 'Daily unattended maintenance registered at 02:00 server local time.'
