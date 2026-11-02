param([string]$Root = 'C:\ETFQuantLab')
. "$PSScriptRoot\common.ps1"
Assert-EtfRoot $Root
Read-Environment $Root
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$destination = "$Root\backup\$stamp"
New-Item -ItemType Directory -Force $destination | Out-Null
# 备份含机密配置，ACL仅管理员、SYSTEM与本项目受信任的专用部署账号。
Invoke-Checked 'icacls.exe' @($destination, '/inheritance:r', '/grant:r', '*S-1-5-18:(OI)(CI)F', '*S-1-5-32-544:(OI)(CI)F')
$deployUser = Get-LocalUser etfdeploy -ErrorAction SilentlyContinue
if ($deployUser) { Invoke-Checked 'icacls.exe' @($destination, '/grant', "*$($deployUser.SID.Value):(OI)(CI)M") }
Copy-Item "$Root\config\runtime.json" "$destination\runtime.json"
if (Test-Path "$Root\app\current.json") { Copy-Item "$Root\app\current.json" "$destination\current.json" }
$env:PGPASSWORD = $env:ETF_DB_PASSWORD
try {
    Invoke-Checked "$Root\runtime\pgsql\bin\pg_dump.exe" @('-h','127.0.0.1','-p','55432','-U','etf_app',
        '-d','etf_quant_lab','-Fc','-f',"$destination\database.dump")
    Invoke-Checked "$Root\runtime\pgsql\bin\pg_restore.exe" @('--list',"$destination\database.dump")
} finally { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
# 版本化模型必须连同序列化工件与冻结输入备份，不能只保存JSON元数据。
$modelDestination = "$destination\models"
New-Item -ItemType Directory -Force $modelDestination | Out-Null
Get-ChildItem "$Root\models" -File -Recurse | Where-Object {$_.Extension -ne '.tmp'} | ForEach-Object {
    # 基金工件位于fund-nav子目录；保留相对路径，避免不同模型目录重名或漏备份。
    $relative = $_.FullName.Substring(("$Root\models\").Length)
    $target = Join-Path $modelDestination $relative
    New-Item -ItemType Directory -Force (Split-Path -Parent $target) | Out-Null
    Copy-Item -LiteralPath $_.FullName -Destination $target
}
if (Test-Path "$Root\app\current.json") {
    $current = Get-Content "$Root\app\current.json" -Raw | ConvertFrom-Json
    if ($current.id -notmatch '^[A-Za-z0-9._-]+$') { throw 'Invalid current release in backup.' }
    # Windows上的运行中JAR可能禁止Compress-Archive重新打开；备份已冻结发布包，不中断服务。
    $package = "$Root\staging\$($current.id).zip"
    if (-not (Test-Path $package)) { throw 'Current immutable application package is unavailable; backup aborted.' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($package)
    try {
        $entry = $archive.GetEntry('release.json')
        if (-not $entry) { throw 'Current application package lacks release metadata.' }
        $reader = [IO.StreamReader]::new($entry.Open())
        try { $metadata = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
        if ($metadata.id -ne $current.id) { throw 'Application package release identity mismatch.' }
    } finally { $archive.Dispose() }
    Copy-Item $package "$destination\application.zip"
}
$artifacts = @(Get-ChildItem $modelDestination -File -Recurse | ForEach-Object {
    @{name=$_.FullName.Substring(($modelDestination+'\').Length);bytes=$_.Length;sha256=(Get-FileHash $_.FullName -Algorithm SHA256).Hash}
})
Write-Utf8Json "$destination\backup-manifest.json" @{
    created_at=(Get-Date).ToUniversalTime().ToString('o');
    database_sha256=(Get-FileHash "$destination\database.dump" -Algorithm SHA256).Hash;
    model_artifacts=$artifacts;
    application_included=(Test-Path "$destination\application.zip");
    application_sha256=$(if (Test-Path "$destination\application.zip") {
        (Get-FileHash "$destination\application.zip" -Algorithm SHA256).Hash
    } else { $null });
    boundary='Database, application and all immutable research models. TLS/ACME credentials remain separately managed.'
}
Write-Output "Backup verified: $destination"
