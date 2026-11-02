param([string]$Root = 'C:\ETFQuantLab')
. "$PSScriptRoot\common.ps1"
Assert-EtfRoot $Root
foreach ($name in @('ETFPostgreSQL','ETFQuantEngine','ETFQuantBackend')) {
    if ((Get-Service $name).Status -ne 'Running') { Start-Service $name }
}
Wait-Health
Write-Output 'All ETF services healthy.'
