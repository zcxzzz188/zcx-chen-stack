#requires -Version 7.0
<#
.SYNOPSIS
    ChenStack.ps1 命令行层的端到端验收（隔离实例 + docker 替身 + 真实 loopback HTTP）。

.DESCRIPTION
    每个用例构造独立的 .runtime/<临时实例名>/.env 与假 Docker 场景，用子进程运行真实脚本：
      * 验证 doctor 的退出码与摘要；
      * 验证 up 在“Running + 内部健康 UP + 端口未发布”时不会报告成功；
      * 验证 repair-ports 默认只诊断、受控实例拒绝、失败有次数上限、成功路径可验证；
      * 验证诊断不写剪贴板、不泄漏口令、不执行删除命令；
      * 验证不存在的实例不会创建 .runtime 目录。

    不会接触正式实例，不会执行任何 docker 写操作（docker 为测试替身）。
    输出格式：TEST|<用例名>|PASS/FAIL。任一断言失败时进程退出码为 1。
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false) } catch { }

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$scriptPath = Join-Path $repoRoot 'script/deploy/ChenStack.ps1'
$supportDir = Join-Path $PSScriptRoot 'support'
$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('chen-stack-cli-tests-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $tempRoot -Force | Out-Null

$script:HttpProcesses = [System.Collections.Generic.List[object]]::new()
$script:InstanceDirs = [System.Collections.Generic.List[string]]::new()
$script:CaseName = ''
$script:CaseFailures = 0
$script:TotalCases = 0
$script:FailedCases = 0
$script:OriginalPath = $env:PATH

. (Join-Path $supportDir 'fake-docker-core.ps1')

# ---------------------------------------------------------------- 断言

function Start-Case {
    param([string]$Name)
    $script:CaseName = $Name
    $script:CaseFailures = 0
    $script:TotalCases++
}

function Assert-That {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) {
        $script:CaseFailures++
        Write-Host "    ASSERT FAIL: $Message" -ForegroundColor Red
    }
}

function Assert-Equal {
    param($Expected, $Actual, [string]$Message)
    Assert-That ($Expected -eq $Actual) "$Message (expected=[$Expected] actual=[$Actual])"
}

function Assert-Contains {
    param([string]$Text, [string]$Needle, [string]$Message)
    Assert-That ($Text -and $Text.Contains($Needle)) "$Message (缺少 [$Needle])"
}

function Assert-NotContains {
    param([string]$Text, [string]$Needle, [string]$Message)
    Assert-That (-not ($Text -and $Text.Contains($Needle))) "$Message (不应出现 [$Needle])"
}

function Complete-Case {
    if ($script:CaseFailures -eq 0) {
        Write-Output "TEST|$($script:CaseName)|PASS"
    } else {
        $script:FailedCases++
        Write-Output "TEST|$($script:CaseName)|FAIL"
    }
}

# ---------------------------------------------------------------- 基础设施

function Get-FreePort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    $listener.Stop()
    return $port
}

function Start-FakeHttpServer {
    param(
        [int]$Port,
        [ValidateSet('UP', 'DOWN')][string]$HealthStatus = 'UP'
    )

    $arguments = @(
        '-NoProfile', '-NonInteractive', '-File', (Join-Path $supportDir 'fake-http.ps1'),
        '-Port', [string]$Port, '-HealthStatus', $HealthStatus
    )

    $process = Start-Process -FilePath 'pwsh' -PassThru -WindowStyle Hidden -ArgumentList $arguments
    $script:HttpProcesses.Add($process)
    $deadline = (Get-Date).AddSeconds(15)
    do {
        try {
            $client = [System.Net.Sockets.TcpClient]::new()
            $client.Connect('127.0.0.1', $Port)
            $client.Close()
            return $process
        } catch {
            Start-Sleep -Milliseconds 100
        }
    } while ((Get-Date) -lt $deadline)
    throw "测试 HTTP 服务未能在端口 $Port 启动"
}

function New-TestInstance {
    <# 创建隔离 .runtime 实例、写 env 与场景文件，返回用例上下文。 #>
    param(
        [int]$BackendPort,
        [int]$AdminPort,
        [int]$UserPort,
        [hashtable]$Scenario
    )

    $instance = 'dsh-test-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
    $runtimeDir = Join-Path $repoRoot ".runtime/$instance"
    New-Item -ItemType Directory -Path $runtimeDir -Force | Out-Null
    $script:InstanceDirs.Add($runtimeDir)

    Set-Content -LiteralPath (Join-Path $runtimeDir '.env') -Encoding utf8NoBOM -Value @(
        "BACKEND_PORT=$BackendPort",
        "ADMIN_PORT=$AdminPort",
        "USER_PORT=$UserPort",
        'AI_KNOWLEDGE_MODE=hybrid'
    )

    $scenarioPath = Join-Path $tempRoot "$instance-scenario.json"
    $statePath = Join-Path $tempRoot "$instance-state.json"
    $callLogPath = Join-Path $tempRoot "$instance-calls.log"
    Set-Content -LiteralPath $callLogPath -Value '' -Encoding utf8

    $instanceCase = [pscustomobject]@{
        Instance     = $instance
        RuntimeDir   = $runtimeDir
        BackendPort  = $BackendPort
        AdminPort    = $AdminPort
        UserPort     = $UserPort
        ScenarioPath = $scenarioPath
        StatePath    = $statePath
        CallLogPath  = $callLogPath
    }
    Save-Scenario -Case $instanceCase -Scenario $Scenario
    return $instanceCase
}

function Save-Scenario {
    param([object]$Case, [hashtable]$Scenario)
    $Scenario | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $Case.ScenarioPath -Encoding utf8
    Copy-Item -LiteralPath $Case.ScenarioPath -Destination $Case.StatePath -Force
}

function New-CaseWithPorts {
    <# 选空闲端口 → 建实例 → 生成健康场景。 #>
    param([hashtable]$ScenarioOverride = @{})

    $backendPort = Get-FreePort
    $adminPort = Get-FreePort
    $userPort = Get-FreePort
    $scenario = New-FakeScenario -Instance 'placeholder' -BackendPort $backendPort -AdminPort $adminPort -UserPort $userPort
    $case = New-TestInstance -Scenario $scenario -BackendPort $backendPort -AdminPort $adminPort -UserPort $userPort
    $scenario = New-FakeScenario -Instance $case.Instance -BackendPort $backendPort -AdminPort $adminPort -UserPort $userPort
    foreach ($key in $ScenarioOverride.Keys) { $scenario[$key] = $ScenarioOverride[$key] }
    Save-Scenario -Case $case -Scenario $scenario
    return [pscustomobject]@{
        Case = $case; Scenario = $scenario
        BackendPort = $backendPort; AdminPort = $adminPort; UserPort = $userPort
    }
}

function Invoke-ChenStack {
    <# 用 docker 替身 PATH 运行真实脚本，返回 exit/stdout/stderr。 #>
    param([object]$Case, [string[]]$Arguments)

    $env:CHEN_FAKE_DOCKER_SCENARIO = $Case.ScenarioPath
    $env:CHEN_FAKE_DOCKER_STATE = $Case.StatePath
    $env:CHEN_FAKE_DOCKER_CALLLOG = $Case.CallLogPath
    $env:PATH = "$supportDir;$script:OriginalPath"

    $all = @('-NoProfile', '-NonInteractive', '-File', $scriptPath) + $Arguments
    $raw = & pwsh @all 2>&1
    $exitCode = $LASTEXITCODE
    $text = (@($raw) | ForEach-Object { [string]$_ }) -join "`n"

    $env:PATH = $script:OriginalPath
    return [pscustomobject]@{ ExitCode = $exitCode; Text = $text }
}

function Get-CallLog {
    param([object]$Case)
    if (-not (Test-Path -LiteralPath $Case.CallLogPath)) { return @() }
    return @(Get-Content -LiteralPath $Case.CallLogPath -Encoding utf8 | Where-Object { $_.Trim() })
}

function Assert-NoDestructiveCalls {
    param([object]$Case)
    foreach ($call in (Get-CallLog -Case $Case)) {
        $verb = ($call -split '\s+')[0]
        Assert-That ($verb -notin @('rm', 'rmi', 'compose', 'volume', 'image', 'tag', 'prune', 'system', 'kill', 'down')) "不得执行破坏性命令：$call"
    }
}

function Get-SummaryLines {
    param([string]$Text)
    $lines = @()
    foreach ($line in ($Text -split "`n")) {
        $trimmed = $line.Trim()
        if ($trimmed -match '^(CHEN_STACK_DOCTOR|BACKEND_INTERNAL_HEALTH|BACKEND_HOST_ACCESS|PORT_[A-Z0-9_]+_PUBLISHED|FRONTEND_USER|FRONTEND_ADMIN|FRONTEND_REQUEST_TARGET|ROOT_CAUSE|RECOMMENDED_ACTION|INSTANCE|BACKEND_PORT|RELEASE_MANAGED|CHEN_STACK_REPAIR|REPAIR_ATTEMPTS|IMAGE_IDENTITY_UNCHANGED|CONTAINER_STILL_RUNNING|NEXT_STEP)=') {
            $lines += $trimmed
        }
    }
    return $lines
}

function Set-BackendPortUnpublished {
    param([hashtable]$Scenario, [string]$BackendPort)
    (Get-FakeContainer -Scenario $Scenario -Name 'chen-stack-backend').publishedBindings = @{ '5000/tcp' = @() }
    $Scenario.http.Remove($BackendPort)
}

# ---------------------------------------------------------------- 用例

try {
    Start-Case 'CLI-A-健康实例 doctor'
    $healthy = New-CaseWithPorts
    [void](Start-FakeHttpServer -Port $healthy.BackendPort)
    [void](Start-FakeHttpServer -Port $healthy.AdminPort)
    [void](Start-FakeHttpServer -Port $healthy.UserPort)
    $result = Invoke-ChenStack -Case $healthy.Case -Arguments @('-Action', 'doctor', '-Instance', $healthy.Case.Instance, '-NoClipboard')
    Assert-Equal 0 $result.ExitCode '退出码'
    Assert-Contains $result.Text 'CHEN_STACK_DOCTOR=PASS' '结论'
    Assert-Contains $result.Text 'BACKEND_INTERNAL_HEALTH=UP' '内部健康'
    Assert-Contains $result.Text 'BACKEND_HOST_ACCESS=PASS' '宿主机访问'
    Assert-Contains $result.Text 'FRONTEND_USER=PASS' '用户端'
    Assert-Contains $result.Text 'FRONTEND_ADMIN=PASS' '管理端'
    Assert-Contains $result.Text 'ROOT_CAUSE=NONE' '根因'
    Assert-Contains $result.Text ("PORT_{0}_PUBLISHED=YES" -f $healthy.BackendPort) '动态端口摘要'
    Assert-Contains $result.Text 'PORT_5000_PUBLISHED=N/A' '非 5000 实例标记'
    Assert-Contains $result.Text 'CLIPBOARD=SKIPPED' '剪贴板跳过'
    Assert-NotContains $result.Text 'NotARealSecret123' '不得泄漏环境口令'
    Assert-NotContains $result.Text 'Unhandled exception' '不应出现未处理异常'
    Assert-NoDestructiveCalls -Case $healthy.Case
    Complete-Case

    Start-Case 'CLI-B-端口未发布必须 FAIL'
    $broken = New-CaseWithPorts
    Set-BackendPortUnpublished -Scenario $broken.Scenario -BackendPort ([string]$broken.BackendPort)
    Save-Scenario -Case $broken.Case -Scenario $broken.Scenario
    [void](Start-FakeHttpServer -Port $broken.AdminPort)
    [void](Start-FakeHttpServer -Port $broken.UserPort)

    $result = Invoke-ChenStack -Case $broken.Case -Arguments @('-Action', 'doctor', '-Instance', $broken.Case.Instance, '-NoClipboard')
    Assert-Equal 1 $result.ExitCode '退出码必须非 0'
    Assert-Contains $result.Text 'CHEN_STACK_DOCTOR=FAIL' '结论'
    Assert-Contains $result.Text 'ROOT_CAUSE=DOCKER_PORT_PUBLISH_MISSING' '根因'
    Assert-Contains $result.Text 'BACKEND_HOST_ACCESS=FAIL' '宿主机访问'
    Assert-Contains $result.Text ("PORT_{0}_PUBLISHED=NO" -f $broken.BackendPort) '端口未发布'
    Assert-Contains $result.Text 'repair-ports' '修复建议'
    Assert-Contains $result.Text '可能原因' '可能原因说明'
    Assert-NoDestructiveCalls -Case $broken.Case
    Complete-Case

    Start-Case 'CLI-C-up 不得在端口未发布时报成功'
    $result = Invoke-ChenStack -Case $broken.Case -Arguments @('-Action', 'up', '-Instance', $broken.Case.Instance)
    Assert-Equal 1 $result.ExitCode 'up 退出码必须非 0'
    Assert-Contains $result.Text '校验未通过' '必须报告校验失败'
    Assert-Contains $result.Text 'ROOT_CAUSE=DOCKER_PORT_PUBLISH_MISSING' '失败原因'
    Assert-NotContains $result.Text '操作完成：up' '不得打印成功'
    Assert-Equal 0 @(Get-CallLog -Case $broken.Case | Where-Object { $_ -match '^restart ' }).Count 'up 不得重启容器'
    Assert-NoDestructiveCalls -Case $broken.Case
    Complete-Case

    Start-Case 'CLI-D-Docker未启动'
    $offline = New-CaseWithPorts
    $offline.Scenario.engine.ok = $false
    Save-Scenario -Case $offline.Case -Scenario $offline.Scenario
    $result = Invoke-ChenStack -Case $offline.Case -Arguments @('-Action', 'doctor', '-Instance', $offline.Case.Instance, '-NoClipboard')
    Assert-Equal 1 $result.ExitCode '退出码必须非 0'
    Assert-Contains $result.Text 'DOCKER_ENGINE_UNAVAILABLE' '根因'
    Assert-Contains $result.Text 'CHEN_STACK_DOCTOR=FAIL' '结论'
    Assert-NotContains $result.Text 'PropertyNotFound' '不应出现内部错误'
    Complete-Case

    Start-Case 'CLI-E-repair 默认只诊断'
    $dryRun = New-CaseWithPorts
    Set-BackendPortUnpublished -Scenario $dryRun.Scenario -BackendPort ([string]$dryRun.BackendPort)
    Save-Scenario -Case $dryRun.Case -Scenario $dryRun.Scenario
    [void](Start-FakeHttpServer -Port $dryRun.AdminPort)
    [void](Start-FakeHttpServer -Port $dryRun.UserPort)
    $result = Invoke-ChenStack -Case $dryRun.Case -Arguments @('-Action', 'repair-ports', '-Instance', $dryRun.Case.Instance, '-NoClipboard')
    Assert-Equal 0 $result.ExitCode '只诊断退出码'
    Assert-Contains $result.Text 'CHEN_STACK_REPAIR=DRY_RUN' '结果'
    Assert-Contains $result.Text 'docker restart --timeout 30' '应给出待执行命令'
    Assert-Equal 0 @(Get-CallLog -Case $dryRun.Case | Where-Object { $_ -match '^restart ' }).Count '只诊断不得重启'
    Complete-Case

    Start-Case 'CLI-F-repair 受控实例未授权必须拒绝'
    $releaseCase = New-CaseWithPorts
    Set-BackendPortUnpublished -Scenario $releaseCase.Scenario -BackendPort ([string]$releaseCase.BackendPort)
    Save-Scenario -Case $releaseCase.Case -Scenario $releaseCase.Scenario
    [void](Start-FakeHttpServer -Port $releaseCase.AdminPort)
    [void](Start-FakeHttpServer -Port $releaseCase.UserPort)
    $result = Invoke-ChenStack -Case $releaseCase.Case -Arguments @('-Action', 'repair-ports', '-Apply', '-Instance', $releaseCase.Case.Instance, '-NoClipboard')
    Assert-Equal 1 $result.ExitCode '拒绝时退出码'
    Assert-Contains $result.Text 'CHEN_STACK_REPAIR=REFUSED' '结果'
    Assert-Contains $result.Text 'AllowReleaseRestart' '授权参数提示'
    Assert-Equal 0 @(Get-CallLog -Case $releaseCase.Case | Where-Object { $_ -match '^restart ' }).Count '未授权不得重启'
    Complete-Case

    Start-Case 'CLI-G-repair 失败有次数上限'
    $bounded = New-CaseWithPorts
    Set-BackendPortUnpublished -Scenario $bounded.Scenario -BackendPort ([string]$bounded.BackendPort)
    (Get-FakeContainer -Scenario $bounded.Scenario -Name 'chen-stack-backend').mounts = @(
        @{ Destination = '/ai-usage'; Source = 'x-ai-usage'; RW = $true; Type = 'bind' }
    )
    $bounded.Scenario.repairEffect = 'none'
    Save-Scenario -Case $bounded.Case -Scenario $bounded.Scenario
    [void](Start-FakeHttpServer -Port $bounded.AdminPort)
    [void](Start-FakeHttpServer -Port $bounded.UserPort)
    $result = Invoke-ChenStack -Case $bounded.Case -Arguments @('-Action', 'repair-ports', '-Apply', '-MaxAttempts', '1', '-Instance', $bounded.Case.Instance, '-NoClipboard')
    Assert-Equal 1 $result.ExitCode '失败时退出码'
    Assert-Contains $result.Text 'CHEN_STACK_REPAIR=FAILED' '结果'
    Assert-Contains $result.Text 'REPAIR_ATTEMPTS=1' '尝试次数'
    Assert-Equal 1 @(Get-CallLog -Case $bounded.Case | Where-Object { $_ -match '^restart ' }).Count '重启次数'
    Assert-NoDestructiveCalls -Case $bounded.Case
    Complete-Case

    Start-Case 'CLI-H-真实端口被占用时拒绝恢复'
    $conflict = New-CaseWithPorts
    Set-BackendPortUnpublished -Scenario $conflict.Scenario -BackendPort ([string]$conflict.BackendPort)
    (Get-FakeContainer -Scenario $conflict.Scenario -Name 'chen-stack-backend').mounts = @(
        @{ Destination = '/ai-usage'; Source = 'x-ai-usage'; RW = $true; Type = 'bind' }
    )
    $conflict.Scenario.repairEffect = 'fix'
    Save-Scenario -Case $conflict.Case -Scenario $conflict.Scenario
    # 在真实 loopback 端口上启动一个与 Docker 无关的监听进程，模拟“端口被其他程序占用”。
    [void](Start-FakeHttpServer -Port $conflict.BackendPort -HealthStatus 'DOWN')
    [void](Start-FakeHttpServer -Port $conflict.AdminPort)
    [void](Start-FakeHttpServer -Port $conflict.UserPort)

    $doctor = Invoke-ChenStack -Case $conflict.Case -Arguments @('-Action', 'doctor', '-Instance', $conflict.Case.Instance, '-NoClipboard')
    Assert-Equal 1 $doctor.ExitCode '诊断退出码'
    Assert-Contains $doctor.Text 'ROOT_CAUSE=WINDOWS_PORT_CONFLICT' '必须识别为端口占用'
    Assert-Contains $doctor.Text '由 pwsh' '冲突提示应包含占用进程信息'

    $repair = Invoke-ChenStack -Case $conflict.Case -Arguments @('-Action', 'repair-ports', '-Apply', '-Instance', $conflict.Case.Instance, '-NoClipboard')
    Assert-Equal 1 $repair.ExitCode '拒绝时退出码'
    Assert-Contains $repair.Text 'CHEN_STACK_REPAIR=REFUSED' '结果'
    Assert-Contains $repair.Text '占用' '应说明端口占用'
    Assert-Equal 0 @(Get-CallLog -Case $conflict.Case | Where-Object { $_ -match '^restart ' }).Count '端口被占用时不得重启'
    Assert-NoDestructiveCalls -Case $conflict.Case
    Complete-Case
    Start-Case 'CLI-I-未初始化实例不创建目录'
    $instance = 'dsh-test-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
    $runtimeDir = Join-Path $repoRoot ".runtime/$instance"
    $scenario = @{
        instance     = $instance
        engine       = @{ ok = $true; version = '29.5.2' }
        containers   = @()
        http         = @{}
        portOwners   = @{}
        excluded     = @()
        dns          = @{}
        repairEffect = 'none'
    }
    $scenarioPath = Join-Path $tempRoot "$instance-scenario.json"
    $statePath = Join-Path $tempRoot "$instance-state.json"
    $callLogPath = Join-Path $tempRoot "$instance-calls.log"
    $scenario | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $scenarioPath -Encoding utf8
    Copy-Item -LiteralPath $scenarioPath -Destination $statePath
    Set-Content -LiteralPath $callLogPath -Value '' -Encoding utf8
    $case = [pscustomobject]@{
        Instance = $instance; RuntimeDir = $runtimeDir; BackendPort = 5000; AdminPort = 8000; UserPort = 7000
        ScenarioPath = $scenarioPath; StatePath = $statePath; CallLogPath = $callLogPath
    }
    $result = Invoke-ChenStack -Case $case -Arguments @('-Action', 'doctor', '-Instance', $instance, '-NoClipboard')
    Assert-Equal 1 $result.ExitCode '退出码'
    Assert-Contains $result.Text 'ROOT_CAUSE=CONTAINERS_NOT_CREATED' '根因'
    Assert-That (-not (Test-Path -LiteralPath $runtimeDir)) 'doctor 不得创建 .runtime 目录'
    Complete-Case

    Start-Case 'CLI-J-诊断摘要幂等'
    $first = Invoke-ChenStack -Case $healthy.Case -Arguments @('-Action', 'doctor', '-Instance', $healthy.Case.Instance, '-NoClipboard')
    $second = Invoke-ChenStack -Case $healthy.Case -Arguments @('-Action', 'doctor', '-Instance', $healthy.Case.Instance, '-NoClipboard')
    $firstSummary = (Get-SummaryLines -Text $first.Text) -join "`n"
    $secondSummary = (Get-SummaryLines -Text $second.Text) -join "`n"
    Assert-Equal $first.ExitCode $second.ExitCode '两次退出码一致'
    Assert-Equal $firstSummary $secondSummary '两次摘要一致'
    Complete-Case
} finally {
    foreach ($process in $script:HttpProcesses) {
        try { Stop-Process -Id $process.Id -Force -ErrorAction Stop } catch { }
    }
    foreach ($directory in $script:InstanceDirs) {
        $full = [System.IO.Path]::GetFullPath($directory)
        $runtimeRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot '.runtime'))
        if ($full.StartsWith($runtimeRoot + [System.IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -and
            (Split-Path -Leaf $full) -like 'dsh-test-*') {
            Remove-Item -LiteralPath $full -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    $env:PATH = $script:OriginalPath
    foreach ($name in @('CHEN_FAKE_DOCKER_SCENARIO', 'CHEN_FAKE_DOCKER_STATE', 'CHEN_FAKE_DOCKER_CALLLOG')) {
        Remove-Item ("Env:$name") -ErrorAction SilentlyContinue
    }
}

Write-Output "SUMMARY|total=$script:TotalCases|failed=$script:FailedCases"
if ($script:FailedCases -gt 0) { exit 1 }
exit 0





