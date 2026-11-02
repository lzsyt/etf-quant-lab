param([string]$Root = 'C:\ETFQuantLab')
. "$PSScriptRoot\common.ps1"
Assert-EtfRoot $Root
$settings = Get-Content "$Root\config\public-web.json" -Raw | ConvertFrom-Json
if ($settings.domain -notmatch '^[a-z0-9.-]+$') { throw 'Invalid domain.' }
Set-Location "$Root\runtime\win-acme"
# 仅向免费CA提交本项目域名；域名控制权由Nginx的HTTP-01挑战文件验证。
# 不填写未经用户提供的邮箱，不强制重复签发已有有效证书。
$arguments = @('--source','manual','--host',$settings.domain,
    '--validation','filesystem','--webroot',"$Root\data\acme-webroot",'--store','pemfiles',
    '--pemfilespath',"$Root\config\tls",'--pemfilesname','etf','--csr','ec',
    '--installation','script','--script',"$Root\runtime\admin-scripts\activate-public-web.ps1",
    '--accepttos','--notaskscheduler','--friendlyname','ETFQuantLab')
# win-acme首次注册仍会询问可选邮箱；空行表示不提供邮箱，避免无人值守任务等待输入。
'' | & "$Root\runtime\win-acme\wacs.exe" @arguments
if ($LASTEXITCODE -ne 0) { throw 'Certificate issuance failed.' }
if (-not (Test-Path "$Root\config\tls\etf-chain.pem")) { throw 'Certificate was not issued.' }
$response = Invoke-WebRequest -UseBasicParsing -Uri "https://$($settings.domain)/" -TimeoutSec 20
if ($response.StatusCode -ne 200) { throw 'HTTPS installation hook did not activate the site.' }
Write-Output 'Certificate issued and HTTPS activation hook completed.'
