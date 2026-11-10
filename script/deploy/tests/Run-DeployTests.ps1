#requires -Version 7.0
<#
.SYNOPSIS
    辰栈 Docker 部署整改的本地验收入口（隔离测试，不接触正式实例）。

.DESCRIPTION
    依次运行：
      1. ChenStackDoctor.Tests.ps1      单元级故障注入（假 Docker / 假 HTTP，无真实端口）
      2. ChenStackDoctor.Cli.Tests.ps1  命令行级端到端（真实脚本进程 + 隔离 .runtime 实例 + loopback 测试服务）

    测试只使用临时实例名 dsh-test-*，结束后清理；不会启动、停止、重启任何正式容器，
    不会执行 docker 写命令，也不会写入真实剪贴板（统一使用 -NoClipboard）。

    用法：
      pwsh -NoProfile -File ./script/deploy/tests/Run-DeployTests.ps1
      pwsh -NoProfile -File ./script/deploy/tests/Run-DeployTests.ps1 -UnitOnly
#>
[CmdletBinding()]
param(
    [switch]$UnitOnly,
    [switch]$CliOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false) } catch { }

$suites = @()
if (-not $CliOnly) { $suites += 'Test-DeployStaticSafety.ps1' }
if (-not $CliOnly) { $suites += 'ChenStackDoctor.Tests.ps1' }
if (-not $UnitOnly) { $suites += 'ChenStackDoctor.Cli.Tests.ps1' }

$failures = 0
$total = 0

foreach ($suite in $suites) {
    Write-Host ''
    Write-Host ('=' * 72) -ForegroundColor DarkCyan
    Write-Host "  运行 $suite" -ForegroundColor Cyan
    Write-Host ('=' * 72) -ForegroundColor DarkCyan

    $path = Join-Path $PSScriptRoot $suite
    $output = & pwsh -NoProfile -NonInteractive -File $path 2>&1
    $exitCode = $LASTEXITCODE
    $lines = @($output | ForEach-Object { [string]$_ })

    foreach ($line in $lines) {
        if ($line -like 'TEST|*') {
            $parts = $line.Split('|')
            $status = $parts[-1]
            $name = $parts[1]
            $total++
            if ($status -eq 'PASS') {
                Write-Host "  PASS  $name" -ForegroundColor Green
            } else {
                $failures++
                Write-Host "  FAIL  $name" -ForegroundColor Red
            }
        } elseif ($line -like 'ASSERT FAIL*') {
            Write-Host "        $line" -ForegroundColor Yellow
        } elseif ($line -like 'SUMMARY|*') {
            Write-Host "  $line" -ForegroundColor DarkGray
        }
    }

    if ($exitCode -ne 0) { $failures += 0; }

    if ($exitCode -ne 0 -and -not ($lines | Where-Object { $_ -like 'TEST|*' })) {
        Write-Host "  $suite 非正常退出（exit=$exitCode）" -ForegroundColor Red
        foreach ($line in ($lines | Select-Object -Last 12)) { Write-Host "    $line" -ForegroundColor DarkYellow }
        $failures++
    }
}

Write-Host ''
Write-Host ('=' * 72) -ForegroundColor DarkCyan
if ($failures -eq 0) {
    Write-Host "  ALL_DEPLOY_TESTS=PASS（$total 个用例）" -ForegroundColor Green
    exit 0
}
Write-Host "  ALL_DEPLOY_TESTS=FAIL（$failures 个失败 / $total 个用例）" -ForegroundColor Red
exit 1

