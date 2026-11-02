param([string]$Root = 'C:\ETFQuantLab')
. "$PSScriptRoot\common.ps1"
Assert-EtfRoot $Root
$settings = Get-Content "$Root\config\public-web.json" -Raw | ConvertFrom-Json
if ($settings.domain -notmatch '^[a-z0-9.-]+$') { throw 'Invalid domain.' }
$configuration = "$Root\config\nginx\nginx.conf"
$previous = [IO.File]::ReadAllText($configuration)
$template = [IO.File]::ReadAllText("$Root\config\nginx\https-template.conf")
# 续期生成的私钥只允许管理员、SYSTEM及实际运行Nginx的LocalService读取。
Invoke-Checked 'icacls.exe' @("$Root\config\tls", '/inheritance:r', '/grant:r',
    '*S-1-5-18:(OI)(CI)F', '*S-1-5-32-544:(OI)(CI)F', '*S-1-5-19:(OI)(CI)R')
try {
    [IO.File]::WriteAllText($configuration, $template.Replace('@DOMAIN@', $settings.domain), [Text.UTF8Encoding]::new($false))
    Invoke-Checked "$Root\runtime\nginx\nginx.exe" @('-p', "$Root/runtime/nginx/", '-c', $configuration, '-t')
    # Windows的Nginx控制事件归LocalService所有，管理员直接-s reload会被拒绝。
    # 通过服务控制管理器让原服务账户停止/启动，保持私钥和应用目录的最小权限。
    Restart-Service ETFQuantWeb
    $ready = $false
    for ($attempt = 0; $attempt -lt 15; $attempt++) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri "https://$($settings.domain)/" -TimeoutSec 5
            if ($response.StatusCode -eq 200) { $ready = $true; break }
        } catch { }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'HTTPS activation check failed.' }
    Write-Utf8Json "$Root\config\public-web-activation.json" @{
        certificateSha256=(Get-FileHash "$Root\config\tls\etf-chain.pem" -Algorithm SHA256).Hash
        activatedAt=(Get-Date).ToUniversalTime().ToString('o')
    }
} catch {
    [IO.File]::WriteAllText($configuration, $previous, [Text.UTF8Encoding]::new($false))
    Restart-Service ETFQuantWeb
    throw
}
Write-Output 'HTTPS configuration validated, service restarted and HTTPS response verified.'
