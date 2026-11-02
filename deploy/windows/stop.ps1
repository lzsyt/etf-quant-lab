param([string]$Root = 'C:\ETFQuantLab')
. "$PSScriptRoot\common.ps1"
Assert-EtfRoot $Root
# 正常发布只停止本工程应用，数据库及既有研究历史保持运行和保留。
foreach ($name in @('ETFQuantBackend','ETFQuantEngine')) {
    $service = Get-Service $name -ErrorAction SilentlyContinue
    if ($service -and $service.Status -ne 'Stopped') {
        Stop-Service $name
        $service.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(45))
    }
}
Write-Output 'ETF application services stopped.'
