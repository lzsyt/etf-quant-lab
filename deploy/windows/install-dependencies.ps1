param([string]$Root = 'C:\ETFQuantLab', [string]$Artifacts = 'C:\ETFQuantLab\staging\dependencies')
. "$PSScriptRoot\common.ps1"
Assert-EtfRoot $Root
$manifest = Get-Content "$Artifacts\artifacts.json" -Raw | ConvertFrom-Json
foreach ($entry in $manifest.PSObject.Properties) {
    $file = Join-Path $Artifacts $entry.Value.filename
    if ((Get-FileHash $file -Algorithm SHA256).Hash.ToLower() -ne $entry.Value.sha256) {
        throw "Artifact checksum failed: $($entry.Name)"
    }
}
New-Item -ItemType Directory -Force "$Root\runtime" | Out-Null
$runtime = "$Root\runtime"
if (-not (Test-Path "$runtime\java\bin\java.exe")) {
    Expand-Archive (Join-Path $Artifacts $manifest.java.filename) "$runtime\java-extract" -Force
    $folder = Get-ChildItem "$runtime\java-extract" -Directory | Select-Object -First 1
    Move-Item $folder.FullName "$runtime\java"
}
if (-not (Test-Path "$runtime\python\python.exe")) {
    Invoke-Checked 'tar.exe' @('-xzf', (Join-Path $Artifacts $manifest.python.filename), '-C', $runtime)
}
if (-not (Test-Path "$runtime\pgsql\bin\postgres.exe")) {
    Expand-Archive (Join-Path $Artifacts $manifest.postgres.filename) $runtime -Force
}
if (-not (Test-Path "$env:WINDIR\System32\vcruntime140.dll") -or
    -not (Test-Path "$env:WINDIR\System32\vcomp140.dll") -or
    -not (Test-Path "$env:WINDIR\System32\vcruntime140_1.dll")) {
    $installer = Join-Path $Artifacts $manifest.vcredist.filename
    $signature = Get-AuthenticodeSignature $installer
    if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'Microsoft') {
        throw 'Microsoft VC runtime signature check failed.'
    }
    $process = Start-Process $installer -ArgumentList '/install','/quiet','/norestart' -Wait -PassThru
    if ($process.ExitCode -notin @(0, 3010, 1638)) { throw 'VC runtime installation failed.' }
}
$venv = "$runtime\venv-v1"
if (-not (Test-Path "$venv\Scripts\python.exe")) {
    Invoke-Checked "$runtime\python\python.exe" @('-m', 'venv', $venv)
}
Invoke-Checked "$venv\Scripts\python.exe" @('-m', 'pip', 'install', '--no-index', '--find-links', "$Artifacts\wheels",
    '-r', "$Artifacts\requirements-windows.lock")
Invoke-Checked "$venv\Scripts\python.exe" @('-m', 'pip', 'check')
Invoke-Checked "$venv\Scripts\python.exe" @('-c', "import fastapi,pandas,numpy,sklearn,lightgbm,xgboost,akshare; print('Python CPU dependencies OK')")
Invoke-Checked "$runtime\java\bin\java.exe" @('-version')
Invoke-Checked "$runtime\pgsql\bin\postgres.exe" @('--version')
Copy-Item (Join-Path $Artifacts $manifest.winsw.filename) "$runtime\WinSW.exe" -Force
Write-Output 'Native dependencies verified; no global Java/Python defaults changed.'
