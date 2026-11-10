#requires -Version 7.0
<#
.SYNOPSIS
    doctor / repair-ports 的隔离故障注入测试（不需要真实 Docker 与真实端口）。

.DESCRIPTION
    使用 tests/support/fake-docker-core.ps1 提供的测试替身，覆盖：
      A 全部正常；B 端口实际绑定缺失；C Windows 端口被占用；D 后端内部健康 DOWN；
      E Docker Engine 不可用；F 前端不可访问 / 前端请求目标不一致；
      G 自动恢复有次数上限；H 受控发布实例边界；I 历史容器不被误认；
      J 诊断与恢复幂等。另含脱敏与只读性断言。

    输出格式：TEST|<用例名>|PASS/FAIL。任一断言失败时进程退出码为 1。
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'support/fake-docker-core.ps1')
. (Join-Path $PSScriptRoot '../ChenStackDoctor.ps1')

$script:Scenario = $null
$script:Calls = [System.Collections.Generic.List[string]]::new()
$script:CaseName = ''
$script:CaseFailures = 0
$script:TotalCases = 0
$script:FailedCases = 0

# ---------------------------------------------------------------- 测试替身接线

function Fake-DockerProbe {
    param([string[]]$Arguments)
    $script:Calls.Add(($Arguments -join ' '))
    return Invoke-FakeDocker -Scenario $script:Scenario -Arguments $Arguments
}

function Fake-HttpProbe {
    param([string]$Uri, [int]$TimeoutSeconds)
    return Invoke-FakeHttp -Scenario $script:Scenario -Uri $Uri
}

function Fake-PortOwnerProbe {
    param([int]$Port)
    if ($script:Scenario.portOwners.ContainsKey([string]$Port)) {
        return $script:Scenario.portOwners[[string]$Port]
    }
    return @{ Supported = $true; Listening = $false; Pid = 0; ProcessName = ''; LocalAddress = '' }
}

function Fake-ExcludedProbe {
    return @($script:Scenario.excluded)
}

function New-TestContext {
    param(
        [hashtable]$Environment = @{ BACKEND_PORT = '5000'; ADMIN_PORT = '8000'; USER_PORT = '7000' },
        [string]$Instance = 'dev'
    )
    $repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
    return New-ChenStackDoctorContext -RepoRoot $repoRoot -Instance $Instance -EnvMap $Environment `
        -Docker ${function:Fake-DockerProbe} -HttpGet ${function:Fake-HttpProbe} `
        -PortOwner ${function:Fake-PortOwnerProbe} -ExcludedRanges ${function:Fake-ExcludedProbe}
}

function Reset-Scenario {
    param([hashtable]$Scenario)
    $script:Scenario = $Scenario
    $script:Calls.Clear()
}

function Get-ScenarioContainer {
    param([string]$Name)
    return Get-FakeContainer -Scenario $script:Scenario -Name $Name
}

# ---------------------------------------------------------------- 断言框架

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

function Complete-Case {
    if ($script:CaseFailures -eq 0) {
        Write-Output "TEST|$($script:CaseName)|PASS"
    } else {
        $script:FailedCases++
        Write-Output "TEST|$($script:CaseName)|FAIL"
    }
}

function Assert-NoDestructiveCalls {
    foreach ($call in $script:Calls) {
        $verb = ($call -split '\s+')[0]
        Assert-That ($verb -notin @('rm', 'rmi', 'compose', 'volume', 'image', 'tag', 'prune', 'system', 'kill', 'down')) "不得执行破坏性命令：$call"
    }
}

function Assert-ReadOnlyCalls {
    foreach ($call in $script:Calls) {
        $verb = ($call -split '\s+')[0]
        Assert-That ($verb -notin @('restart', 'start', 'stop', 'kill', 'rm', 'compose', 'volume', 'tag', 'run', 'create', 'update', 'prune')) "诊断不应执行写命令：$call"
    }
}

# ---------------------------------------------------------------- 用例

Start-Case 'A-健康部署'
$scenario = New-FakeScenario
Reset-Scenario $scenario
$result = Invoke-ChenStackDoctor -Context (New-TestContext)
Assert-That $result.Ok 'Ok 应为 true'
Assert-Equal 'NONE' $result.RootCause 'ROOT_CAUSE'
Assert-Equal 'UP' $result.InternalHealth '内部健康'
Assert-Equal 'PASS' $result.HostAccess '宿主机访问'
Assert-Equal $true $result.BackendPublished '实际端口发布'
Assert-Equal 'PASS' $result.FrontendUser '用户端'
Assert-Equal 'PASS' $result.FrontendAdmin '管理端'
Assert-Equal 'CONSISTENT' $result.RequestTarget '前端请求目标'
$joined = $result.Summary -join "`n"
Assert-Contains $joined 'CHEN_STACK_DOCTOR=PASS' '摘要'
Assert-Contains $joined 'PORT_5000_PUBLISHED=YES' '摘要端口'
Assert-Contains $joined 'BACKEND_INTERNAL_HEALTH=UP' '摘要健康'
Assert-Contains $joined 'ROOT_CAUSE=NONE' '摘要根因'
Assert-ReadOnlyCalls
Complete-Case

Start-Case 'B-端口绑定缺失'
$scenario = New-FakeScenario
(Get-ScenarioContainer 'chen-stack-backend').publishedBindings = @{ '5000/tcp' = @() }
$scenario.http.Remove('5000')
Reset-Scenario $scenario
$result = Invoke-ChenStackDoctor -Context (New-TestContext)
Assert-That (-not $result.Ok) 'Ok 必须为 false'
Assert-Equal 'DOCKER_PORT_PUBLISH_MISSING' $result.RootCause 'ROOT_CAUSE'
Assert-Equal $false $result.BackendPublished '实际端口发布'
Assert-Equal $true $result.HostBindingOk 'HostConfig 仍应有绑定'
Assert-Equal 'FAIL' $result.HostAccess '宿主机访问'
Assert-Equal 'UP' $result.InternalHealth '内部健康仍 UP'
$joined = $result.Summary -join "`n"
Assert-Contains $joined 'PORT_5000_PUBLISHED=NO' '摘要端口'
Assert-Contains $joined 'BACKEND_HOST_ACCESS=FAIL' '摘要访问'
Assert-Contains $result.RecommendedAction 'repair-ports' '修复建议应指向 repair-ports'
Assert-ReadOnlyCalls
Complete-Case

Start-Case 'C-Windows端口被占用'
$scenario = New-FakeScenario
(Get-ScenarioContainer 'chen-stack-backend').publishedBindings = @{ '5000/tcp' = @() }
$scenario.http.Remove('5000')
$scenario.portOwners['5000'] = @{ Supported = $true; Listening = $true; Pid = 4242; ProcessName = 'othersvc'; LocalAddress = '127.0.0.1' }
Reset-Scenario $scenario
$result = Invoke-ChenStackDoctor -Context (New-TestContext)
Assert-Equal 'WINDOWS_PORT_CONFLICT' $result.RootCause 'ROOT_CAUSE'
Assert-Equal 'FAIL' $result.HostAccess '宿主机访问'
$joined = $result.Summary -join "`n"
Assert-Contains $joined 'WINDOWS_PORT_CONFLICT' '摘要根因'
Assert-ReadOnlyCalls
Complete-Case

Start-Case 'D-后端内部健康DOWN'
$scenario = New-FakeScenario
(Get-ScenarioContainer 'chen-stack-backend').internalHealth = 'DOWN'
$scenario.http['5000'] = @{ status = 200; body = '{"status":"DOWN"}' }
Reset-Scenario $scenario
$result = Invoke-ChenStackDoctor -Context (New-TestContext)
Assert-Equal 'BACKEND_INTERNAL_HEALTH_DOWN' $result.RootCause 'ROOT_CAUSE'
Assert-Equal 'DOWN' $result.InternalHealth '内部健康'
Assert-Equal 'FAIL' $result.HostAccess '宿主机访问'
$joined = $result.Summary -join "`n"
Assert-Contains $joined 'BACKEND_INTERNAL_HEALTH=DOWN' '摘要'
Assert-ReadOnlyCalls
Complete-Case

Start-Case 'E-DockerEngine不可用'
$scenario = New-FakeScenario
$scenario.engine.ok = $false
Reset-Scenario $scenario
$result = Invoke-ChenStackDoctor -Context (New-TestContext)
Assert-That (-not $result.Ok) 'Ok 必须为 false'
Assert-Equal 'DOCKER_ENGINE_UNAVAILABLE' $result.RootCause 'ROOT_CAUSE'
Assert-Equal 'UNKNOWN' $result.InternalHealth '内部健康未知'
Assert-Equal 'FAIL' $result.HostAccess '宿主机访问'
$joined = $result.Summary -join "`n"
Assert-Contains $joined 'CHEN_STACK_DOCTOR=FAIL' '摘要'
Assert-Contains $joined 'DOCKER_ENGINE_UNAVAILABLE' '摘要根因'
Assert-That ($result.Checks.Count -ge 1) '至少应有一条检查项'
Complete-Case

Start-Case 'F1-用户端不可访问'
$scenario = New-FakeScenario
$scenario.http.Remove('7000')
Reset-Scenario $scenario
$result = Invoke-ChenStackDoctor -Context (New-TestContext)
Assert-Equal 'FAIL' $result.FrontendUser '用户端'
Assert-Equal 'PASS' $result.FrontendAdmin '管理端仍应 PASS'
Assert-Equal 'FRONTEND_NGINX_UNREACHABLE' $result.RootCause 'ROOT_CAUSE'
$joined = $result.Summary -join "`n"
Assert-Contains $joined 'FRONTEND_USER=FAIL' '摘要'
Assert-ReadOnlyCalls
Complete-Case

Start-Case 'F2-前端请求目标不一致'
$scenario = New-FakeScenario
(Get-ScenarioContainer 'chen-stack-user').backendUrl = 'http://127.0.0.1:5999'
(Get-ScenarioContainer 'chen-stack-admin').backendUrl = 'http://127.0.0.1:5999'
Reset-Scenario $scenario
$result = Invoke-ChenStackDoctor -Context (New-TestContext)
Assert-Equal 'MISMATCH' $result.RequestTarget '请求目标'
Assert-That (-not $result.Ok) 'Ok 必须为 false'
Assert-Equal 'FRONTEND_REQUEST_TARGET_MISMATCH' $result.RootCause 'ROOT_CAUSE'
$joined = $result.Summary -join "`n"
Assert-Contains $joined 'FRONTEND_REQUEST_TARGET=MISMATCH' '摘要'
Complete-Case

Start-Case 'G-历史容器不被误认'
$scenario = New-FakeScenario
$scenario.containers += (New-FakeContainer -Name 'chen-knowledge-r1-backend' -Service 'backend' -Project 'dev' `
        -State 'exited' -Running $false -HostBindings @{} -PublishedBindings @{} `
        -ImageId 'sha256:8888888888888888888888888888888888888888888888888888888888888888')
Reset-Scenario $scenario
$result = Invoke-ChenStackDoctor -Context (New-TestContext)
Assert-Equal 'chen-stack-backend' $result.BackendName '应选中运行中的正式容器'
Assert-Equal 1 $result.Historical.Count '历史容器数量'
Assert-Equal 'chen-knowledge-r1-backend' $result.Historical[0].Name '历史容器名'
Assert-That $result.Ok '历史容器不应导致 FAIL'
Assert-Equal 'NONE' $result.RootCause 'ROOT_CAUSE'
$historicalCheck = @($result.Checks | Where-Object { $_.Label -eq '历史容器' })[0]
Assert-Equal 'WARN' $historicalCheck.Status '历史容器应为 WARN'
Complete-Case

Start-Case 'H-实例尚未创建'
$scenario = New-FakeScenario
$scenario.containers = @()
Reset-Scenario $scenario
$result = Invoke-ChenStackDoctor -Context (New-TestContext)
Assert-Equal 'CONTAINERS_NOT_CREATED' $result.RootCause 'ROOT_CAUSE'
Assert-That ($result.MissingServices -contains 'backend') '应报告缺少 backend'
Assert-Contains $result.RecommendedAction '-Action init' '建议 init'
Complete-Case

Start-Case 'I-端口绑定缺失但宿主机可访问'
$scenario = New-FakeScenario
(Get-ScenarioContainer 'chen-stack-backend').publishedBindings = @{ '5000/tcp' = @() }
Reset-Scenario $scenario
$result = Invoke-ChenStackDoctor -Context (New-TestContext)
Assert-Equal 'PORT_PUBLISH_UNVERIFIED' $result.RootCause 'ROOT_CAUSE'
Assert-Equal 'PASS' $result.HostAccess '宿主机访问仍 PASS'
Assert-Equal $false $result.BackendPublished '端口发布缺失'
Complete-Case

Start-Case 'J-端口配置与容器不一致'
$scenario = New-FakeScenario
(Get-ScenarioContainer 'chen-stack-backend').hostBindings = @{ '5000/tcp' = @(@{ HostIp = '127.0.0.1'; HostPort = '4999' }) }
(Get-ScenarioContainer 'chen-stack-backend').publishedBindings = @{ '5000/tcp' = @(@{ HostIp = '127.0.0.1'; HostPort = '4999' }) }
Reset-Scenario $scenario
$result = Invoke-ChenStackDoctor -Context (New-TestContext)
Assert-Equal 'PORT_CONFIG_MISMATCH' $result.RootCause 'ROOT_CAUSE'
Assert-Equal $false $result.HostBindingOk 'HostConfig 应不匹配'
Complete-Case

Start-Case 'K-端口排除范围提示'
$scenario = New-FakeScenario
$scenario.excluded = @(@{ Start = 5000; End = 5059; Administered = $false })
Reset-Scenario $scenario
$result = Invoke-ChenStackDoctor -Context (New-TestContext)
$excludedCheck = @($result.Checks | Where-Object { $_.Label -eq '端口排除范围' })[0]
Assert-Equal 'WARN' $excludedCheck.Status '排除范围应为 WARN'
Assert-Equal 'NONE' $result.RootCause '排除范围不应改变正常结论'
Complete-Case

Start-Case 'L-隔离端口摘要'
$scenario = New-FakeScenario -Instance 'chenstack-test' -BackendPort 29106 -AdminPort 29107 -UserPort 29108
Reset-Scenario $scenario
$result = Invoke-ChenStackDoctor -Context (New-TestContext -Environment @{ BACKEND_PORT = '29106'; ADMIN_PORT = '29107'; USER_PORT = '29108' } -Instance 'chenstack-test')
$joined = $result.Summary -join "`n"
Assert-Contains $joined 'PORT_5000_PUBLISHED=N/A' '非 5000 实例'
Assert-Contains $joined 'PORT_29106_PUBLISHED=YES' '动态端口键'
Assert-Equal 'NONE' $result.RootCause 'ROOT_CAUSE'
Complete-Case

Start-Case 'M-日志关键错误脱敏'
$scenario = New-FakeScenario
(Get-ScenarioContainer 'chen-stack-backend').internalHealth = 'DOWN'
(Get-ScenarioContainer 'chen-stack-backend').logs = @(
    '2026-11-10 08:00:00 ERROR Caused by: Access denied for user root password=SuperSecret123 token=abc123456'
    '2026-11-10 08:00:01 ERROR APPLICATION FAILED TO START'
) -join "`n"
$scenario.http.Remove('5000')
Reset-Scenario $scenario
$result = Invoke-ChenStackDoctor -Context (New-TestContext)
$findingsText = ($result.LogFindings -join "`n")
Assert-That ($findingsText.Length -gt 0) '应捕获关键错误行'
Assert-That (-not $findingsText.Contains('SuperSecret123')) '日志输出不得包含口令'
Assert-That (-not $findingsText.Contains('abc123456')) '日志输出不得包含 Token'
Assert-Contains $findingsText 'APPLICATION FAILED TO START' '应保留关键错误'
Complete-Case

Start-Case 'N-诊断幂等'
$scenario = New-FakeScenario
Reset-Scenario $scenario
$first = Invoke-ChenStackDoctor -Context (New-TestContext)
$firstSummary = $first.Summary -join "`n"
$firstCalls = $script:Calls.Count
$callsBeforeSecond = $script:Calls.Count
$second = Invoke-ChenStackDoctor -Context (New-TestContext)
$secondSummary = $second.Summary -join "`n"
Assert-Equal $firstSummary $secondSummary '两次摘要应一致'
Assert-Equal $first.Ok $second.Ok '两次结论应一致'
Assert-Equal $firstCalls ($script:Calls.Count - $callsBeforeSecond) '两次调用的 docker 次数应一致'
Assert-ReadOnlyCalls
Complete-Case

Start-Case 'O-恢复默认只诊断'
$scenario = New-FakeScenario
(Get-ScenarioContainer 'chen-stack-backend').publishedBindings = @{ '5000/tcp' = @() }
$scenario.http.Remove('5000')
Reset-Scenario $scenario
$repair = Invoke-ChenStackPortRepair -Context (New-TestContext) -ProbeTimeoutSeconds 1 -ProbeIntervalSeconds 0
Assert-Equal 'DRY_RUN' $repair.Outcome '结果'
Assert-Equal 0 $repair.Attempts '尝试次数'
Assert-That ($repair.Preview.Count -gt 0) '应给出待执行命令'
Assert-That (@($script:Calls | Where-Object { $_ -match '\brestart\b' }).Count -eq 0) '只诊断时不得重启容器'
Assert-Contains ($repair.Summary -join "`n") 'CHEN_STACK_REPAIR=DRY_RUN' '摘要'
Complete-Case

Start-Case 'P-受控发布实例拒绝重启'
$scenario = New-FakeScenario
(Get-ScenarioContainer 'chen-stack-backend').publishedBindings = @{ '5000/tcp' = @() }
$scenario.http.Remove('5000')
Reset-Scenario $scenario
$repair = Invoke-ChenStackPortRepair -Context (New-TestContext) -Apply -ProbeTimeoutSeconds 1 -ProbeIntervalSeconds 0
Assert-Equal 'REFUSED' $repair.Outcome '结果'
Assert-Equal 0 $repair.Attempts '尝试次数'
Assert-That (@($script:Calls | Where-Object { $_ -match '\brestart\b' }).Count -eq 0) '未获授权时不得重启'
$reasons = $repair.Reasons -join ' '
Assert-Contains $reasons 'AllowReleaseRestart' '应提示显式授权参数'
Complete-Case

Start-Case 'Q-受控发布实例授权后恢复成功'
$scenario = New-FakeScenario
(Get-ScenarioContainer 'chen-stack-backend').publishedBindings = @{ '5000/tcp' = @() }
$scenario.http.Remove('5000')
$scenario.repairEffect = 'fix'
Reset-Scenario $scenario
$repair = Invoke-ChenStackPortRepair -Context (New-TestContext) -Apply -AllowReleaseRestart -ProbeTimeoutSeconds 2 -ProbeIntervalSeconds 0
Assert-Equal 'SUCCESS' $repair.Outcome '结果'
Assert-Equal 1 $repair.Attempts '尝试次数'
Assert-That $repair.Post.Ok '恢复后诊断应 PASS'
$joined = $repair.Summary -join "`n"
Assert-Contains $joined 'IMAGE_IDENTITY_UNCHANGED=YES' '镜像身份'
Assert-Contains $joined 'CONTAINER_STILL_RUNNING=YES' '容器运行'
Assert-Contains $joined 'PORT_5000_PUBLISHED=YES' '端口发布'
Assert-Contains $joined 'BACKEND_HOST_ACCESS=PASS' '宿主机访问'
$restartCalls = @($script:Calls | Where-Object { $_ -match '^restart ' })
Assert-Equal 1 $restartCalls.Count 'restart 次数'
Assert-NoDestructiveCalls
Assert-Equal 'YES' (@($repair.Verifications)[-1].ImageUnchanged) '验证镜像未变'
Complete-Case

Start-Case 'R-自动恢复失败有次数上限'
$scenario = New-FakeScenario
(Get-ScenarioContainer 'chen-stack-backend').mounts = @(@{ Destination = '/ai-usage'; Source = 'x-ai-usage'; RW = $true; Type = 'bind' })
(Get-ScenarioContainer 'chen-stack-backend').publishedBindings = @{ '5000/tcp' = @() }
$scenario.http.Remove('5000')
$scenario.repairEffect = 'none'
Reset-Scenario $scenario
$repair = Invoke-ChenStackPortRepair -Context (New-TestContext) -Apply -MaxAttempts 2 -ProbeTimeoutSeconds 1 -ProbeIntervalSeconds 0
Assert-Equal 'FAILED' $repair.Outcome '结果'
Assert-Equal 2 $repair.Attempts '尝试次数应被上限截断'
$restartCalls = @($script:Calls | Where-Object { $_ -match '^restart ' })
Assert-Equal 2 $restartCalls.Count '重启次数不得超过上限'
Assert-NoDestructiveCalls
Assert-Contains ($repair.Summary -join "`n") 'CHEN_STACK_REPAIR=FAILED' '摘要'
Complete-Case

Start-Case 'S-镜像身份变化立即停止'
$scenario = New-FakeScenario
(Get-ScenarioContainer 'chen-stack-backend').mounts = @(@{ Destination = '/ai-usage'; Source = 'x-ai-usage'; RW = $true; Type = 'bind' })
(Get-ScenarioContainer 'chen-stack-backend').publishedBindings = @{ '5000/tcp' = @() }
$scenario.http.Remove('5000')
$scenario.repairEffect = 'image-change'
Reset-Scenario $scenario
$repair = Invoke-ChenStackPortRepair -Context (New-TestContext) -Apply -MaxAttempts 3 -ProbeTimeoutSeconds 1 -ProbeIntervalSeconds 0
Assert-Equal 'FAILED' $repair.Outcome '结果'
Assert-Equal 1 $repair.Attempts '镜像变化后应停止重试'
$joined = $repair.Summary -join "`n"
Assert-Contains $joined 'IMAGE_IDENTITY_UNCHANGED=NO' '摘要'
Assert-Contains ($repair.Reasons -join ' ') '镜像身份变化' '原因说明'
Complete-Case

Start-Case 'T-无需恢复'
$scenario = New-FakeScenario
Reset-Scenario $scenario
$repair = Invoke-ChenStackPortRepair -Context (New-TestContext) -Apply -AllowReleaseRestart
Assert-Equal 'NOT_NEEDED' $repair.Outcome '结果'
Assert-That (@($script:Calls | Where-Object { $_ -match '\brestart\b' }).Count -eq 0) '健康时不得重启'
Complete-Case

Start-Case 'U-脱敏函数'
Reset-Scenario (New-FakeScenario)
$text = 'password=abc123; Authorization: Bearer eyJhbGciOiJIUzI1NiJ9abc; api_key=sk-xyz; MAIL_PASSWORD=mailpass'
$sanitized = Protect-DoctorText $text
Assert-That (-not $sanitized.Contains('abc123')) '密码应脱敏'
Assert-That (-not $sanitized.Contains('sk-xyz')) 'API Key 应脱敏'
Assert-That (-not $sanitized.Contains('mailpass')) '邮件凭据应脱敏'
Assert-That (-not $sanitized.Contains('eyJhbGciOiJIUzI1NiJ9abc')) 'JWT 应脱敏'
Complete-Case

# ---------------------------------------------------------------- 汇总

Write-Output "SUMMARY|total=$script:TotalCases|failed=$script:FailedCases"
if ($script:FailedCases -gt 0) { exit 1 }
exit 0



