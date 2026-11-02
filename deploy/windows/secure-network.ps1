param([string]$Root = 'C:\ETFQuantLab', [Parameter(Mandatory=$true)][string[]]$TrustedAddress)
. "$PSScriptRoot\common.ps1"
Assert-EtfRoot $Root
$origin = ($env:SSH_CONNECTION -split ' ')[0]
if ($origin -and $origin -notin $TrustedAddress) {
    throw 'The current verified SSH source must be included before applying firewall restrictions.'
}
$code = @'
import ipaddress,json,sys
trusted=[ipaddress.ip_address(a) for a in sys.argv[1:]]
if not trusted or any(a.version != 4 or a.is_unspecified for a in trusted):
    raise ValueError('Only explicit trusted IPv4 hosts are accepted')
networks=[ipaddress.ip_network('0.0.0.0/0')]
for address in trusted+[ipaddress.ip_address('127.0.0.1')]:
    excluded=ipaddress.ip_network(str(address)+'/32')
    networks=[n for group in (list(n.address_exclude(excluded)) if excluded.subnet_of(n) else [n] for n in networks) for n in group]
# Windows防火墙拒绝IPv6的/0前缀；两个/1覆盖同一地址空间。
print(json.dumps([str(n) for n in networks]+['::/1','8000::/1']))
'@
$denied = & "$Root\runtime\python\python.exe" -c $code @TrustedAddress | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or $denied.Count -lt 2) { throw 'Management allowlist validation failed.' }
if (-not (Test-Path "$Root\backup\firewall-before-etf.wfw")) {
    Invoke-Checked 'netsh.exe' @('advfirewall','export',"$Root\backup\firewall-before-etf.wfw")
}
foreach ($rule in @('ETF-Management-Allow','ETF-Management-BlockOutside','ETF-RDP-UDP-BlockOutside')) {
    Get-NetFirewallRule -Name $rule -ErrorAction SilentlyContinue | Remove-NetFirewallRule
}
New-NetFirewallRule -Name 'ETF-Management-Allow' -DisplayName 'ETF trusted SSH and RDP' -Direction Inbound `
    -Action Allow -Protocol TCP -LocalPort 22,3389 -RemoteAddress $TrustedAddress -Profile Any | Out-Null
# 显式阻止其他来源，避免系统既有宽泛Allow规则使白名单失效；不删除其他服务规则。
New-NetFirewallRule -Name 'ETF-Management-BlockOutside' -DisplayName 'ETF restrict SSH and RDP sources' -Direction Inbound `
    -Action Block -Protocol TCP -LocalPort 22,3389 -RemoteAddress $denied -Profile Any | Out-Null
New-NetFirewallRule -Name 'ETF-RDP-UDP-BlockOutside' -DisplayName 'ETF restrict RDP UDP sources' -Direction Inbound `
    -Action Block -Protocol UDP -LocalPort 3389 -RemoteAddress $denied -Profile Any | Out-Null
Set-NetFirewallProfile -Profile Domain,Private,Public -Enabled True -DefaultInboundAction Block -DefaultOutboundAction Allow
Write-Utf8Json "$Root\config\management-allowlist.json" @{trusted=$TrustedAddress;applied_at=(Get-Date).ToUniversalTime().ToString('o')}
Write-Output 'Firewall enabled with SSH/RDP source restrictions. Keep the current SSH session until a second connection succeeds.'
