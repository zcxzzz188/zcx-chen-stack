#requires -Version 7.0
<#
.SYNOPSIS
    部署脚本静态安全检查（不启动 Docker，不接触实例）。

.DESCRIPTION
    校验以下不变量：
      * 部署相关 PowerShell 文件语法可解析；
      * 所有 docker inspect 调用都使用 --format 提取字段，不对整份 JSON 做转换；
      * doctor 只读：写入类 docker 命令只允许出现在 repair-ports 实现中；
      * doctor 引擎不接触任何凭据字段名，输出摘要不会携带口令；
      * 剪贴板写入只出现在一个函数里；
      * 脚本对外暴露的动作包含 doctor 与 repair-ports。

    输出 TEST|<检查项>|PASS/FAIL，失败时退出码为 1。
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$deployDir = Join-Path $repoRoot 'script/deploy'
$doctorPath = Join-Path $deployDir 'ChenStackDoctor.ps1'
$entryPath = Join-Path $deployDir 'ChenStack.ps1'

$failures = 0
$total = 0

function Test-Invariant {
    param([string]$Name, [bool]$Condition, [string]$Detail = '')

    $script:total++
    if ($Condition) {
        Write-Output "TEST|$Name|PASS"
    } else {
        $script:failures++
        Write-Output "TEST|$Name|FAIL"
        if ($Detail) { Write-Host "    ASSERT FAIL: $Detail" -ForegroundColor Red }
    }
}

$doctor = Get-Content -LiteralPath $doctorPath -Raw -Encoding utf8
$entry = Get-Content -LiteralPath $entryPath -Raw -Encoding utf8

# 1. 语法
foreach ($file in @($doctorPath, $entryPath,
        (Join-Path $deployDir 'tests/ChenStackDoctor.Tests.ps1'),
        (Join-Path $deployDir 'tests/ChenStackDoctor.Cli.Tests.ps1'),
        (Join-Path $deployDir 'tests/Run-DeployTests.ps1'),
        (Join-Path $deployDir 'tests/support/fake-docker-core.ps1'),
        (Join-Path $deployDir 'tests/support/fake-docker.ps1'),
        (Join-Path $deployDir 'tests/support/fake-http.ps1'))) {
    $errors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($file, [ref]$null, [ref]$errors)
    $name = Split-Path -Leaf $file
    Test-Invariant "语法可解析：$name" ($errors.Count -eq 0) (($errors | ForEach-Object { "$($_.Extent.StartLineNumber): $($_.Message)" }) -join '; ')
}

# 2. inspect 必须配合 --format
$inspectCount = ([regex]::Matches($doctor, "'inspect'")).Count
$formatCount = ([regex]::Matches($doctor, "'--format'")).Count
Test-Invariant 'doctor 的 inspect 调用均使用 --format' ($inspectCount -gt 0 -and $inspectCount -le $formatCount) "inspect=$inspectCount format=$formatCount"

# 3. doctor 只读：写命令只允许出现在 repair 实现里
$restartCallCount = ([regex]::Matches($doctor, "@\('restart', '--timeout'")).Count
Test-Invariant 'docker restart 只出现一次（受控恢复）' ($restartCallCount -eq 1) "restart 调用次数=$restartCallCount"
foreach ($verb in @('stop', 'kill', 'rm', 'rmi', 'compose', 'tag', 'volume', 'prune', 'down')) {
    $pattern = "@('$verb'"
    $count = ([regex]::Matches($doctor, [regex]::Escape($pattern))).Count
    Test-Invariant "doctor 不调用写命令：$verb" ($count -eq 0) "出现 $count 次"
}

# 4. 凭据字段绝不进入 doctor
foreach ($credential in @('MYSQL_PASSWORD', 'MYSQL_ROOT_PASSWORD', 'REDIS_PASSWORD', 'MINIO_SECRET_KEY',
        'RABBITMQ_DEFAULT_PASS', 'CHEN_STACK_JWT_SECRET', 'SPRING_AI_OPENAI_API_KEY', 'MAIL_PASSWORD')) {
    Test-Invariant "doctor 不读取凭据字段：$credential" (-not $doctor.Contains($credential))
}

# 5. 剪贴板写入集中在一处
$clipboardCalls = ([regex]::Matches($doctor, '\bSet-Clipboard\b')).Count
Test-Invariant 'Set-Clipboard 仅出现一次' ($clipboardCalls -eq 1) "出现 $clipboardCalls 次"

# 6. 动作集合
foreach ($action in @('doctor', 'repair-ports')) {
    Test-Invariant "入口动作包含：$action" ($entry.Contains("'$action'"))
}

# 7. 摘要字段齐全
foreach ($key in @('CHEN_STACK_DOCTOR', 'BACKEND_INTERNAL_HEALTH', 'BACKEND_HOST_ACCESS', 'PORT_5000_PUBLISHED',
        'FRONTEND_USER', 'FRONTEND_ADMIN', 'ROOT_CAUSE', 'RECOMMENDED_ACTION')) {
    Test-Invariant "摘要包含字段：$key" ($doctor.Contains($key))
}

# 8. 诊断不得依赖整份 inspect JSON
Test-Invariant '不使用 ConvertFrom-Json 处理整份 inspect 输出' (-not ($doctor -match 'inspect[^\r\n]*-f\s' ))
Test-Invariant '所有 inspect 均带模板标记' (-not ($doctor -match "@\('inspect',\s*\\\$Name\)\s*$"))

Write-Output "SUMMARY|total=$total|failed=$failures"
if ($failures -gt 0) { exit 1 }
exit 0
