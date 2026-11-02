param([string]$Root = 'C:\ETFQuantLab')
. "$PSScriptRoot\common.ps1"
Assert-EtfRoot $Root
$OutputEncoding = [Text.UTF8Encoding]::new($false)
# 交互输入不回显，密码通过标准输入传给本机bcrypt，不进入命令参数或日志。
$secure = Read-Host 'New administrator password (at least 16 characters)' -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
    $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    if ($plain.Length -lt 16) { throw 'Password must have at least 16 characters.' }
    $hash = $plain | & "$Root\runtime\venv-v1\Scripts\python.exe" -c "import sys,bcrypt; print(bcrypt.hashpw(sys.stdin.buffer.read().rstrip(b'\r\n'),bcrypt.gensalt()).decode())"
    if ($LASTEXITCODE -ne 0 -or $hash -notmatch '^\$2b\$') { throw 'Password hashing failed.' }
    $configuration = Get-Content "$Root\config\runtime.json" -Raw | ConvertFrom-Json
    $configuration.ETF_ADMIN_PASSWORD_HASH = $hash
    Write-Utf8Json "$Root\config\runtime.json" $configuration
    Invoke-Checked 'icacls.exe' @("$Root\config\runtime.json", '/grant', '*S-1-5-19:R')
    $deployUser = Get-LocalUser etfdeploy -ErrorAction SilentlyContinue
    if ($deployUser) {
        Invoke-Checked 'icacls.exe' @("$Root\config\runtime.json", '/grant', "*$($deployUser.SID.Value):R")
    }
    # 网页改密后的数据库哈希优先于环境配置；应急重设必须同步更新，不能只改旧初始化值。
    Read-Environment $Root
    $env:PGPASSWORD = $env:ETF_DB_PASSWORD
    try {
        $pg = "$Root\runtime\pgsql\bin\psql.exe"
        $arguments = @('-h','127.0.0.1','-p','55432','-U',$env:ETF_DB_USER,'-d','etf_quant_lab','-v','ON_ERROR_STOP=1','-At')
        $table = & $pg @arguments -c "SELECT to_regclass('public.admin_credential')"
        if ($LASTEXITCODE -ne 0) { throw 'Credential storage check failed.' }
        if ($table -eq 'admin_credential') {
            $admin = if ($env:ETF_ADMIN_USER) { $env:ETF_ADMIN_USER } else { 'admin' }
            $escapedAdmin = $admin.Replace("'", "''")
            # 哈希通过标准输入传给psql，不放到命令参数、输出或操作审计正文。
            $sql = @"
BEGIN;
SELECT pg_advisory_xact_lock(hashtextextended('admin-password:$escapedAdmin',0));
UPDATE admin_credential SET password_hash='$hash',updated_at=now() WHERE username='$escapedAdmin';
INSERT INTO admin_credential(username,password_hash)
SELECT '$escapedAdmin','$hash' WHERE NOT EXISTS (SELECT 1 FROM admin_credential WHERE username='$escapedAdmin');
INSERT INTO operation_audit(id,action,details,actor)
VALUES ('$([Guid]::NewGuid())','ADMIN_PASSWORD_RESET',jsonb_build_object('username','$escapedAdmin'),'SERVER_ADMIN');
COMMIT;
"@
            $sql | & $pg @arguments | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'Persisted administrator password update failed.' }
        }
    } finally { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    $plain = $null
}
Restart-Service ETFQuantBackend
Wait-Health
Write-Output 'Administrator password updated; old sessions invalidated by service restart.'
