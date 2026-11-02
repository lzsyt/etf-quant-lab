param(
    [string]$Root = 'C:\ETFQuantLab',
    [Parameter(Mandatory=$true)][string]$SingaporeHost,
    [Parameter(Mandatory=$true)][string]$SshUser
)
. "$PSScriptRoot\common.ps1"
Assert-EtfRoot $Root

# ETF独占的SSH隧道只复用既有新加坡回环代理，不安装、重启或修改共享代理。
# 私钥及已核实的known_hosts由受保护SFTP预置；不自动信任新主机，不读取全局代理。
if ($SingaporeHost -notmatch '^[a-zA-Z0-9.-]+$' -or $SshUser -notmatch '^[a-zA-Z0-9_-]+$') {
    throw 'Invalid SSH destination.'
}
$private = "$Root\config\gpt-proxy"
$serviceHome = "$Root\runtime\gpt-proxy-service"
$ssh = 'C:\Windows\System32\OpenSSH\ssh.exe'
foreach ($file in @($ssh, "$private\id_ed25519", "$private\known_hosts", "$Root\runtime\WinSW.exe")) {
    if (-not (Test-Path -LiteralPath $file)) { throw 'GPT tunnel prerequisite missing.' }
}
Invoke-Checked 'icacls.exe' @($private, '/inheritance:r', '/grant:r', '*S-1-5-18:(OI)(CI)F', '*S-1-5-32-544:(OI)(CI)F')
Invoke-Checked 'icacls.exe' @("$private\id_ed25519", '/inheritance:r', '/grant:r', '*S-1-5-18:F', '*S-1-5-32-544:F')
Invoke-Checked 'icacls.exe' @("$private\id_ed25519", '/setowner', '*S-1-5-18')
New-Item -ItemType Directory -Path $serviceHome -Force | Out-Null
Invoke-Checked 'icacls.exe' @($serviceHome, '/inheritance:r', '/grant:r', '*S-1-5-18:(OI)(CI)F', '*S-1-5-32-544:(OI)(CI)F')
$serviceId = 'ETFGptProxy'
$exe = "$serviceHome\$serviceId.exe"
$existing = Get-Service $serviceId -ErrorAction SilentlyContinue
if ($existing) { Stop-Service $serviceId; $existing.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(30)) }
Copy-Item "$Root\runtime\WinSW.exe" $exe -Force

# 专用受限密钥只允许连接新加坡127.0.0.1:8888；本地仅监听回环17891。
# SYSTEM持有隧道私钥，业务进程只访问回环HTTP代理，无需读取SSH凭据。
@"
<service>
  <id>$serviceId</id><name>$serviceId</name><description>ETF GPT dedicated Singapore SSH tunnel</description>
  <executable>$ssh</executable>
  <arguments>-N -T -o BatchMode=yes -o StrictHostKeyChecking=yes -o ExitOnForwardFailure=yes -o ConnectTimeout=10 -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -o IdentitiesOnly=yes -o UserKnownHostsFile="$private\known_hosts" -i "$private\id_ed25519" -L 127.0.0.1:17891:127.0.0.1:8888 $SshUser@$SingaporeHost</arguments>
  <workingdirectory>$serviceHome</workingdirectory><startmode>Automatic</startmode>
  <onfailure action="restart" delay="10 sec"/><resetfailure>1 hour</resetfailure><stoptimeout>15 sec</stoptimeout>
  <logpath>$Root\logs</logpath><log mode="roll-by-size"><sizeThreshold>1024</sizeThreshold><keepFiles>3</keepFiles></log>
</service>
"@ | Set-Content "$serviceHome\$serviceId.xml" -Encoding UTF8
if (-not $existing) { Invoke-Checked $exe @('install') }
Start-Service $serviceId
for ($attempt=0; $attempt -lt 30; $attempt++) {
    $listener = @([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() |
        Where-Object { $_.Port -eq 17891 -and $_.Address.ToString() -eq '127.0.0.1' })
    if ($listener.Count -gt 0) {
        Write-Output 'ETF GPT tunnel running on loopback; authenticated API verification is still required.'
        exit 0
    }
    Start-Sleep -Seconds 1
}
throw 'ETF GPT tunnel did not become ready; GPT must remain disabled.'
