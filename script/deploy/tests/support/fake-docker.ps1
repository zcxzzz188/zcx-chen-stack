#requires -Version 7.0
<#
.SYNOPSIS
    测试专用 docker 命令行替身（由同目录 docker.cmd 转发）。

.DESCRIPTION
    只用于 script/deploy/tests 的隔离验收；把调用转发给 fake-docker-core.ps1，
    并按环境变量读写场景/状态/调用日志：

        CHEN_FAKE_DOCKER_SCENARIO  初始场景 JSON（必填）
        CHEN_FAKE_DOCKER_STATE     可变状态 JSON（可选，重启后会写回）
        CHEN_FAKE_DOCKER_CALLLOG   调用日志（可选）

    生产部署脚本不会调用本文件。请勿把本目录加入长期 PATH。
#>

. (Join-Path $PSScriptRoot 'fake-docker-core.ps1')

$DockerArguments = @($args | ForEach-Object { [string]$_ })
$scenarioPath = $env:CHEN_FAKE_DOCKER_SCENARIO
$statePath = $env:CHEN_FAKE_DOCKER_STATE
$callLog = $env:CHEN_FAKE_DOCKER_CALLLOG

if (-not $scenarioPath -or -not (Test-Path -LiteralPath $scenarioPath)) {
    [Console]::Error.WriteLine('fake-docker: CHEN_FAKE_DOCKER_SCENARIO 未设置或文件不存在')
    exit 91
}

$sourcePath = $scenarioPath
if ($statePath -and (Test-Path -LiteralPath $statePath)) { $sourcePath = $statePath }
$scenario = Get-Content -LiteralPath $sourcePath -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable

try {
    $result = Invoke-FakeDocker -Scenario $scenario -Arguments $DockerArguments -CallLog $callLog
} catch {
    [Console]::Error.WriteLine("fake-docker: $($_.Exception.Message)")
    exit 93
}

if ($statePath) {
    $scenario | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $statePath -Encoding utf8
}

$text = [string]$result.Output
if ($text) {
    [Console]::Out.WriteLine($text)
}
exit ([int]$result.ExitCode)
