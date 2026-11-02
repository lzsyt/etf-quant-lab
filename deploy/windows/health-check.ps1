param([string]$Root = 'C:\ETFQuantLab')
. "$PSScriptRoot\common.ps1"
Assert-EtfRoot $Root
Wait-Health -Seconds 20
$page = Invoke-WebRequest 'http://127.0.0.1:18080/' -UseBasicParsing -TimeoutSec 10
if ($page.StatusCode -ne 200 -or $page.Content -notmatch '<div id="app">') { throw 'Web page check failed.' }
$unauthorized = $false
try { Invoke-WebRequest 'http://127.0.0.1:18080/api/paper/accounts' -UseBasicParsing -TimeoutSec 5 | Out-Null }
catch { $unauthorized = [int]$_.Exception.Response.StatusCode -eq 401 }
if (-not $unauthorized) { throw 'Unauthenticated business access was not rejected.' }
$listeners = @([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() |
    Where-Object { $_.Port -in @(55432,18080,18081) } |
    Select-Object @{Name='LocalAddress';Expression={$_.Address.ToString()}},@{Name='LocalPort';Expression={$_.Port}})
if ($listeners.Count -lt 3 -or ($listeners | Where-Object { $_.LocalAddress -notin @('127.0.0.1','::1') })) {
    throw 'A business service is missing or exposed beyond loopback.'
}
# 普通部署账号没有系统CIM管理权限；通过已授权的ServiceController及只读注册表核验。
$services = @(foreach ($name in @('ETFPostgreSQL','ETFQuantEngine','ETFQuantBackend')) {
    $service = Get-Service $name
    $configuration = Get-ItemProperty "HKLM:\SYSTEM\CurrentControlSet\Services\$name"
    [pscustomobject]@{Name=$name;State=$service.Status.ToString();
        StartMode=$(if ($configuration.Start -eq 2) {'Auto'} else {'Other'});StartName=$configuration.ObjectName}
})
if ($services.Count -ne 3 -or ($services | Where-Object { $_.State -ne 'Running' -or $_.StartMode -ne 'Auto' })) {
    throw 'Services must be running with automatic startup.'
}
[ordered]@{status='UP';mode='PAPER_ONLY';web=$page.StatusCode;authentication='ENFORCED';
    services=$services;listeners=@($listeners | Select-Object LocalAddress,LocalPort);
    checked_at=(Get-Date).ToUniversalTime().ToString('o')} | ConvertTo-Json -Depth 6
