param([string]$Root = 'C:\ETFQuantLab', [string]$Domain = 'etf.example.invalid',
    [string]$Artifacts = 'C:\ETFQuantLab\staging\public-web')
. "$PSScriptRoot\common.ps1"
Assert-EtfRoot $Root
if ($Domain -notmatch '^[a-z0-9.-]+$') { throw 'Invalid domain.' }
$manifest = Get-Content "$Artifacts\public-web-artifacts.json" -Raw | ConvertFrom-Json
foreach ($entry in $manifest.PSObject.Properties.Value) {
    if ((Get-FileHash "$Artifacts\$($entry.filename)" -Algorithm SHA256).Hash.ToLower() -ne $entry.sha256) {
        throw 'Public web artifact checksum mismatch.'
    }
}
foreach ($directory in @('config\nginx','config\tls','data\acme-webroot','data\acme-state',
    'data\nginx','logs\nginx','runtime\web-service','runtime\win-acme')) {
    New-Item -ItemType Directory -Force "$Root\$directory" | Out-Null
}
# 私钥、ACME账户和管理员续期脚本不授予部署账号或应用写权限。
foreach ($directory in @('config\nginx','config\tls','runtime\web-service','runtime\win-acme')) {
    Invoke-Checked 'icacls.exe' @("$Root\$directory", '/inheritance:r', '/grant:r',
        '*S-1-5-18:(OI)(CI)F', '*S-1-5-32-544:(OI)(CI)F', '*S-1-5-19:(OI)(CI)RX')
}
Invoke-Checked 'icacls.exe' @("$Root\data\acme-state", '/inheritance:r', '/grant:r',
    '*S-1-5-18:(OI)(CI)F', '*S-1-5-32-544:(OI)(CI)F')
foreach ($directory in @('logs\nginx','data\nginx')) {
    Invoke-Checked 'icacls.exe' @("$Root\$directory", '/grant', '*S-1-5-19:(OI)(CI)M')
}
if (-not (Test-Path "$Root\runtime\nginx\nginx.exe")) {
    Expand-Archive "$Artifacts\$($manifest.nginx.filename)" "$Root\runtime\nginx-extract"
    $extracted = Get-ChildItem "$Root\runtime\nginx-extract" -Directory | Select-Object -First 1
    Move-Item $extracted.FullName "$Root\runtime\nginx"
}
if (-not (Test-Path "$Root\runtime\win-acme\wacs.exe")) {
    Expand-Archive "$Artifacts\$($manifest.win_acme.filename)" "$Root\runtime\win-acme" -Force
}
if (-not (Test-Path "$Root\runtime\win-acme\settings.json")) {
    $acmeSettings = Get-Content "$Root\runtime\win-acme\settings_default.json" -Raw | ConvertFrom-Json
    $acmeSettings.Client.ConfigurationPath = "$Root\data\acme-state"
    $acmeSettings.Client.LogPath = "$Root\data\acme-state\logs"
    Write-Utf8Json "$Root\runtime\win-acme\settings.json" $acmeSettings
}
# HTTPS入口使用Secure会话Cookie，只接受本机Nginx转发的协议头；不覆盖现有凭据。
$runtimePath = "$Root\config\runtime.json"
$previousRuntime = [IO.File]::ReadAllText($runtimePath)
$runtimeAcl = Get-Acl $runtimePath
$runtimeConfig = $previousRuntime | ConvertFrom-Json
$changed = $false
foreach ($setting in @(
    @{Name='SERVER_FORWARD_HEADERS_STRATEGY';Value='NATIVE'},
    @{Name='SERVER_SERVLET_SESSION_COOKIE_SECURE';Value='true'}
)) {
    $property = $runtimeConfig.PSObject.Properties[$setting.Name]
    if (-not $property -or [string]$property.Value -ne $setting.Value) {
        $runtimeConfig | Add-Member -NotePropertyName $setting.Name -NotePropertyValue $setting.Value -Force
        $changed = $true
    }
}
if ($changed) {
    try {
        Write-Utf8Json $runtimePath $runtimeConfig
        Set-Acl $runtimePath $runtimeAcl
        Restart-Service ETFQuantBackend
        Wait-Health
    } catch {
        [IO.File]::WriteAllText($runtimePath, $previousRuntime, [Text.UTF8Encoding]::new($false))
        Set-Acl $runtimePath $runtimeAcl
        Restart-Service ETFQuantBackend
        throw
    }
}
Write-Utf8Json "$Root\config\public-web.json" @{domain=$Domain}
Copy-Item "$Artifacts\nginx-https.conf" "$Root\config\nginx\https-template.conf" -Force
foreach ($name in @('activate-public-web.ps1','renew-public-web.ps1')) {
    Copy-Item "$PSScriptRoot\$name" "$Root\runtime\admin-scripts\$name" -Force
}
if (-not (Test-Path "$Root\config\nginx\nginx.conf") -or -not (Test-Path "$Root\config\tls\etf-key.pem")) {
    $template = [IO.File]::ReadAllText("$Artifacts\nginx-bootstrap.conf")
    [IO.File]::WriteAllText("$Root\config\nginx\nginx.conf", $template.Replace('@DOMAIN@', $Domain), [Text.UTF8Encoding]::new($false))
}
Invoke-Checked "$Root\runtime\nginx\nginx.exe" @('-p', "$Root/runtime/nginx/", '-c', "$Root\config\nginx\nginx.conf", '-t')
$service = "$Root\runtime\web-service\ETFQuantWeb.exe"
if (-not (Get-Service ETFQuantWeb -ErrorAction SilentlyContinue)) {
    Copy-Item "$Root\runtime\WinSW.exe" $service
    @"
<service>
  <id>ETFQuantWeb</id><name>ETFQuantWeb</name><description>ETF Quant Lab HTTPS entry</description>
  <executable>$Root\runtime\nginx\nginx.exe</executable>
  <arguments>-p $Root/runtime/nginx/ -c $Root/config/nginx/nginx.conf</arguments>
  <stopexecutable>$Root\runtime\nginx\nginx.exe</stopexecutable>
  <stoparguments>-p $Root/runtime/nginx/ -c $Root/config/nginx/nginx.conf -s quit</stoparguments>
  <workingdirectory>$Root\runtime\nginx</workingdirectory><startmode>Automatic</startmode>
  <serviceaccount><domain>NT AUTHORITY</domain><user>LocalService</user><allowservicelogon>true</allowservicelogon></serviceaccount>
  <onfailure action="restart" delay="5 sec"/><stoptimeout>20 sec</stoptimeout>
  <logpath>$Root\logs\nginx</logpath><log mode="roll-by-size"><sizeThreshold>10240</sizeThreshold><keepFiles>4</keepFiles></log>
</service>
"@ | Set-Content "$Root\runtime\web-service\ETFQuantWeb.xml" -Encoding UTF8
    Invoke-Checked $service @('install')
}
foreach ($port in @(80,443)) {
    if (-not (Get-NetFirewallRule -Name "ETFQuantWeb-$port" -ErrorAction SilentlyContinue)) {
        New-NetFirewallRule -Name "ETFQuantWeb-$port" -DisplayName "ETF Quant Lab public TCP $port" `
            -Direction Inbound -Action Allow -Protocol TCP -LocalPort $port -Profile Any | Out-Null
    }
}
Start-Service ETFQuantWeb
# 管理员专用目录中的脚本由SYSTEM执行，部署账号无法替换任务入口。
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -NonInteractive -File `"$Root\runtime\admin-scripts\renew-public-web.ps1`""
$trigger = New-ScheduledTaskTrigger -Daily -At '04:00'
$options = New-ScheduledTaskSettingsSet -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Minutes 20)
Register-ScheduledTask -TaskName ETFQuantLab-CertificateRenewal -Action $action -Trigger $trigger -Settings $options -User SYSTEM -RunLevel Highest -Force | Out-Null
Write-Output 'Nginx bootstrap installed; HTTP serves certificate challenges only until HTTPS activation.'
