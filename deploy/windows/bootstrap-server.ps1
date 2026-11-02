param([string]$Root = 'C:\ETFQuantLab', [string]$PublicKeyPath, [switch]$PrepareOnly)
. "$PSScriptRoot\common.ps1"
Assert-EtfRoot $Root
$identity = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $identity.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Bootstrap requires an elevated Administrator session.'
}
foreach ($directory in @('', 'app','config','data','logs','models','releases','backup','runtime','staging')) {
    New-Item -ItemType Directory -Force (Join-Path $Root $directory) | Out-Null
}
# 首先收紧根目录，之后才上传配置和密钥；现有文件、数据不会被清空。
Invoke-Checked 'icacls.exe' @($Root, '/inheritance:r', '/grant:r', '*S-1-5-18:(OI)(CI)F', '*S-1-5-32-544:(OI)(CI)F',
    '*S-1-5-19:(OI)(CI)RX', '*S-1-5-20:(OI)(CI)RX')
Invoke-Checked 'icacls.exe' @("$Root\config", '/inheritance:r', '/grant:r', '*S-1-5-18:(OI)(CI)F', '*S-1-5-32-544:(OI)(CI)F')
if ($PrepareOnly) { Write-Output 'Protected installation directories prepared.'; return }
& "$PSScriptRoot\install-dependencies.ps1" -Root $Root
if (-not (Test-Path "$Root\config\runtime.json") -or -not (Test-Path "$Root\config\bootstrap.json")) {
    throw 'Upload protected runtime.json and bootstrap.json before continuing.'
}
Read-Environment $Root
$secrets = Get-Content "$Root\config\bootstrap.json" -Raw | ConvertFrom-Json
$pgBin = "$Root\runtime\pgsql\bin"
$pgData = "$Root\data\postgres"
if (-not (Test-Path "$pgData\PG_VERSION")) {
    # Windows initdb会以移除Administrators组权限的受限令牌重启自身。
    # 显式授权当前用户SID，保证它仍能读取私有运行时并写入本项目的数据目录。
    $bootstrapSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    Invoke-Checked 'icacls.exe' @($Root, '/grant', "*$($bootstrapSid):RX")
    Invoke-Checked 'icacls.exe' @("$Root\runtime", '/grant', "*$($bootstrapSid):(OI)(CI)RX")
    Invoke-Checked 'icacls.exe' @("$Root\data", '/grant', "*$($bootstrapSid):(OI)(CI)M")
    Invoke-Checked 'icacls.exe' @("$Root\config", '/grant', "*$($bootstrapSid):RX")
    # initdb口令通过权限受限的临时文件传入，不进入命令参数或日志。
    $passwordFile = "$Root\config\initdb-password.tmp"
    [IO.File]::WriteAllText($passwordFile, $secrets.pg_owner_password, [Text.UTF8Encoding]::new($false))
    Invoke-Checked 'icacls.exe' @($passwordFile, '/grant', "*$($bootstrapSid):R")
    try {
        Invoke-Checked "$pgBin\initdb.exe" @('-D', $pgData, '-U', 'etf_owner', '--encoding=UTF8', '--locale=C',
            '--auth-host=scram-sha-256', '--auth-local=scram-sha-256', "--pwfile=$passwordFile")
    } finally { Remove-Item -LiteralPath $passwordFile -ErrorAction SilentlyContinue }
    @"
listen_addresses = '127.0.0.1'
port = 55432
timezone = 'UTC'
log_timezone = 'UTC'
shared_buffers = '128MB'
work_mem = '4MB'
maintenance_work_mem = '64MB'
max_connections = 35
max_wal_size = '1GB'
logging_collector = on
log_directory = '$($Root.Replace('\','/'))/logs/postgres'
log_filename = 'postgresql-%Y-%m-%d.log'
log_rotation_age = '1d'
log_rotation_size = '20MB'
"@ | Set-Content "$pgData\postgresql.conf" -Encoding ASCII
}
New-Item -ItemType Directory -Force "$Root\logs\postgres" | Out-Null
Invoke-Checked 'icacls.exe' @($pgData, '/grant', '*S-1-5-20:(OI)(CI)F', '/T', '/Q')
Invoke-Checked 'icacls.exe' @("$Root\logs\postgres", '/grant', '*S-1-5-20:(OI)(CI)M')
if (-not (Get-Service ETFPostgreSQL -ErrorAction SilentlyContinue)) {
    Invoke-Checked "$pgBin\pg_ctl.exe" @('register', '-N', 'ETFPostgreSQL', '-D', $pgData,
        '-U', 'NT AUTHORITY\NetworkService', '-S', 'auto')
}
Start-Service ETFPostgreSQL
$env:PGPASSWORD = $secrets.pg_owner_password
try {
    $role = & "$pgBin\psql.exe" -h 127.0.0.1 -p 55432 -U etf_owner -d postgres -At -c "SELECT 1 FROM pg_roles WHERE rolname='etf_app'"
    if ($LASTEXITCODE -ne 0) { throw 'Database bootstrap connection failed.' }
    if ($role -ne '1') {
        if ($env:ETF_DB_PASSWORD -notmatch '^[A-Za-z0-9_-]{32,}$') { throw 'Invalid generated database credential.' }
        "CREATE ROLE etf_app LOGIN PASSWORD '$env:ETF_DB_PASSWORD';" |
            & "$pgBin\psql.exe" -h 127.0.0.1 -p 55432 -U etf_owner -d postgres -v ON_ERROR_STOP=1 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Database role creation failed.' }
    }
    $database = & "$pgBin\psql.exe" -h 127.0.0.1 -p 55432 -U etf_owner -d postgres -At -c "SELECT 1 FROM pg_database WHERE datname='etf_quant_lab'"
    if ($database -ne '1') {
        Invoke-Checked "$pgBin\createdb.exe" @('-h','127.0.0.1','-p','55432','-U','etf_owner','-O','etf_app','etf_quant_lab')
    }
} finally { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
Invoke-Checked 'icacls.exe' @("$Root\config\runtime.json", '/grant', '*S-1-5-19:R')
Invoke-Checked 'icacls.exe' @("$Root\config", '/grant', '*S-1-5-19:RX')
foreach ($directory in @('logs','models')) {
    Invoke-Checked 'icacls.exe' @("$Root\$directory", '/grant', '*S-1-5-19:(OI)(CI)M')
}

if ($PublicKeyPath) {
    if (-not (Get-LocalUser etfdeploy -ErrorAction SilentlyContinue)) {
        $bytes = New-Object byte[] 36
        [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
        $password = ConvertTo-SecureString ([Convert]::ToBase64String($bytes) + 'aA1!') -AsPlainText -Force
        New-LocalUser etfdeploy -Password $password -PasswordNeverExpires -Description 'ETF Quant Lab key-only deploy' | Out-Null
    }
    $sid = (Get-LocalUser etfdeploy).SID.Value
    # 中文Windows的内置OpenSSH组名可能与sshd默认英文规则不一致，使用本项目独立组。
    if (-not (Get-LocalGroup ETFQuantDeploy -ErrorAction SilentlyContinue)) {
        New-LocalGroup ETFQuantDeploy -Description 'ETF dedicated SSH deploy users' | Out-Null
    }
    if ($sid -notin @(Get-LocalGroupMember ETFQuantDeploy | ForEach-Object { $_.SID.Value })) {
        Add-LocalGroupMember -Group ETFQuantDeploy -Member etfdeploy
    }
    # SSH登录类型不属于Interactive/Service组，显式允许部署账号查询数据库服务状态。
    $descriptor = (& sc.exe sdshow ETFPostgreSQL | Where-Object { $_ -match '^D:' }) -join ''
    if ($descriptor -notmatch [regex]::Escape($sid)) {
        $ace = "(A;;CCLCSWLOCRRC;;;$sid)"
        if ($descriptor.Contains('S:')) { $descriptor = $descriptor.Replace('S:', "$ace`S:") }
        else { $descriptor += $ace }
        Invoke-Checked 'sc.exe' @('sdset', 'ETFPostgreSQL', $descriptor)
    }
    foreach ($directory in @('releases','staging','backup','models')) {
        Invoke-Checked 'icacls.exe' @("$Root\$directory", '/grant', "*$($sid):(OI)(CI)M")
    }
    Invoke-Checked 'icacls.exe' @($Root, '/grant', "*$($sid):RX")
    Invoke-Checked 'icacls.exe' @("$Root\app", '/grant', "*$($sid):(OI)(CI)M")
    Invoke-Checked 'icacls.exe' @("$Root\runtime", '/grant', "*$($sid):(OI)(CI)RX")
    Invoke-Checked 'icacls.exe' @("$Root\config", '/grant', "*$($sid):RX")
    Invoke-Checked 'icacls.exe' @("$Root\config\runtime.json", '/grant', "*$($sid):R")
    $authorized = "$Root\config\deploy_authorized_keys"
    Get-Content $PublicKeyPath | Set-Content $authorized -Encoding ASCII
    Invoke-Checked 'icacls.exe' @($authorized, '/inheritance:r', '/grant:r', '*S-1-5-18:F', '*S-1-5-32-544:F', "*$($sid):R")
    Invoke-Checked 'icacls.exe' @($authorized, '/setowner', "*$sid")
    $sshd = "$env:ProgramData\ssh\sshd_config"
    $sshdChanged = $false
    if (-not (Test-Path "$Root\backup\sshd_config.before-etf")) {
        Copy-Item $sshd "$Root\backup\sshd_config.before-etf" -ErrorAction Stop
    }
    $sshText = Get-Content $sshd -Raw
    if ($sshText -match '(?m)^AllowGroups ' -and $sshText -notmatch '(?mi)^AllowGroups .*etfquantdeploy') {
        $sshText = [regex]::Replace($sshText, '(?m)^(AllowGroups[^\r\n]*)', '$1 etfquantdeploy')
        [IO.File]::WriteAllText($sshd, $sshText, [Text.UTF8Encoding]::new($false))
        $sshdChanged = $true
    }
    if (-not (Select-String -Path $sshd -SimpleMatch 'Match User etfdeploy' -Quiet)) {
        @"

# ETF Quant Lab dedicated deployment account
Match User etfdeploy
    AuthorizedKeysFile C:/ETFQuantLab/config/deploy_authorized_keys
    PasswordAuthentication no
    AllowTcpForwarding yes
    PermitOpen 127.0.0.1:18080
    AllowAgentForwarding no
"@ | Add-Content $sshd -Encoding ASCII
        $sshdChanged = $true
    }
    if ($sshdChanged) {
        Invoke-Checked "$env:WINDIR\System32\OpenSSH\sshd.exe" @('-t')
        Restart-Service sshd
    }
}
Set-Service sshd -StartupType Automatic
Write-Output 'Bootstrap complete; RDP preserved; application services are installed by deploy.ps1.'
