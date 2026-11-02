param([string]$Root = 'C:\ETFQuantLab')
& "$PSScriptRoot\stop.ps1" -Root $Root
& "$PSScriptRoot\start.ps1" -Root $Root
