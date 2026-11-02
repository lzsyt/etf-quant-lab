param([string]$Root = 'C:\ETFQuantLab')
. "$PSScriptRoot\common.ps1"
Assert-EtfRoot $Root
Set-Location "$Root\runtime\win-acme"
# 由服务器SYSTEM计划任务独立续期；不依赖开发机、RDP或用户会话。
& "$Root\runtime\win-acme\wacs.exe" --renew --friendlyname ETFQuantLab --notaskscheduler
if ($LASTEXITCODE -ne 0) { throw 'Certificate renewal check failed.' }
$active = Get-Content "$Root\config\public-web-activation.json" -Raw | ConvertFrom-Json
if ($active.certificateSha256 -ne (Get-FileHash "$Root\config\tls\etf-chain.pem" -Algorithm SHA256).Hash) {
    # win-acme可能在安装钩子失败时仍返回0；明确核对实际启用证书，失败必须让计划任务失败。
    & "$PSScriptRoot\activate-public-web.ps1" -Root $Root
}
$largeLogs = @(Get-ChildItem "$Root\logs\nginx" -File | Where-Object {
    $_.Name -in @('access.log','error.log') -and $_.Length -gt 10MB
})
if ($largeLogs.Count -gt 0) {
    Stop-Service ETFQuantWeb
    try {
        foreach ($file in $largeLogs) {
            Move-Item $file.FullName "$($file.FullName).$((Get-Date).ToString('yyyyMMddHHmmss'))"
        }
    } finally {
        Start-Service ETFQuantWeb
    }
}
# 仅清理本Nginx已轮转日志，不删除应用数据或证书。
Get-ChildItem "$Root\logs\nginx" -File | Where-Object {
    $_.Name -match '^(access|error)\.log\.\d{14}$' -and $_.LastWriteTime -lt (Get-Date).AddDays(-30)
} | Remove-Item -Force
$settings = Get-Content "$Root\config\public-web.json" -Raw | ConvertFrom-Json
$response = Invoke-WebRequest -UseBasicParsing -Uri "https://$($settings.domain)/" -TimeoutSec 20
if ($response.StatusCode -ne 200) { throw 'Public HTTPS health check failed.' }
Write-Output 'Certificate renewal check and web log maintenance completed.'
