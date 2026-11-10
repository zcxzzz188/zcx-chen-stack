#requires -Version 7.0
<#
.SYNOPSIS
    辰栈 (Chen Stack) 本地 Docker 一键部署与运维脚本。

.DESCRIPTION
    四种场景分开处理，互不混用：

      init      首次全新部署：生成 .env 与 Redis ACL、构建产物与镜像、初始化数据库迁移、启动全部服务
      up        日常启动（含电脑重启后）：只启动已存在的容器，不重建、不迁移
      stop      停止容器（保留容器与数据卷）
      restart   只重启应用容器（backend / admin / user）
      upgrade   已有数据环境升级：备份镜像标签 → 重建产物与镜像 → 幂等迁移 → 重建应用容器
      rollback  回滚到上一次 upgrade 之前的应用镜像
      migrate   只执行增量 SQL 迁移（幂等）
      status    查看实例容器、健康与版本
      doctor    只读诊断：Docker、容器、实际端口映射、Windows 监听、内外健康、前端访问与日志
      repair-ports
                端口发布异常的安全恢复：默认只诊断，-Apply 才重启同一个后端容器

    安全边界：
      * 从不执行 docker compose down -v，不删除数据卷。
      * upgrade 会检测“受控发布实例”（带 chen.release 标签、挂载 /release 或启用 RECOVERY），
        这类实例由 script/release 的发布流程管理，脚本直接拒绝，避免运行制品与发布清单不一致。
      * doctor 全程只读；repair-ports 只做 docker restart，不重建容器、不换镜像、不删数据。
      * 只有容器 Running 且容器内健康 UP、宿主端口映射存在、宿主机 HTTP 可访问，才算启动成功。
      * 支持 -Instance 创建隔离实例（不同项目名/容器名/卷名/端口），用于并行验证。

.PARAMETER Action
    init | up | stop | restart | upgrade | rollback | migrate | status | doctor | repair-ports

.PARAMETER Instance
    实例名，默认 dev。非默认值会生成独立的 env/override 文件，并隔离容器、镜像、卷及网络。

.PARAMETER WithRag
    同时启动 compose profile=rag 的本地 Qdrant，并写入 RAG 相关环境变量。
    仍需独立 Embedding 凭据与一次受控知识发布，RAG 才会有可检索的知识。

.PARAMETER SkipBuild
    跳过宿主机产物和应用镜像构建，要求目标实例已有匹配的本地镜像。

.PARAMETER PortBase
    隔离实例首次初始化时使用的起始端口，预留连续 11 个 loopback 端口。

.PARAMETER Apply
    仅用于 repair-ports：真正执行受控重启。缺省只诊断并打印将执行的命令。

.PARAMETER AllowReleaseRestart
    仅用于 repair-ports：允许对受控发布实例重启同一个容器。
    不重建容器、不更换镜像、不改挂载，因此不会改变发布清单绑定的运行制品。

.PARAMETER MaxAttempts
    repair-ports 的最大重启次数，取值 1..3，默认 2。

.PARAMETER NoClipboard
    不自动把摘要写入剪贴板（默认会复制并回读校验）。

.EXAMPLE
    pwsh -File script/deploy/ChenStack.ps1 -Action init

.EXAMPLE
    pwsh -File script/deploy/ChenStack.ps1 -Action up

.EXAMPLE
    pwsh -File script/deploy/ChenStack.ps1 -Action status

.EXAMPLE
    pwsh -File script/deploy/ChenStack.ps1 -Action doctor

.EXAMPLE
    pwsh -File script/deploy/ChenStack.ps1 -Action repair-ports -Apply
#>
[CmdletBinding()]
param(
    [ValidateSet('init', 'up', 'stop', 'restart', 'upgrade', 'rollback', 'migrate', 'status', 'doctor', 'repair-ports')]
    [string]$Action = 'status',
    [ValidatePattern('^[a-z][a-z0-9-]{0,47}$')]
    [string]$Instance = 'dev',
    [switch]$WithRag,
    [switch]$SkipBuild,
    [ValidateRange(1024, 65525)]
    [int]$PortBase,
    [switch]$Apply,
    [switch]$AllowReleaseRestart,
    [ValidateRange(1, 3)]
    [int]$MaxAttempts = 2,
    [switch]$NoClipboard
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
try { [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false) } catch { }

$RepoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$ComposeDir = Join-Path $RepoRoot 'script/dev'
$ComposeFile = Join-Path $ComposeDir 'docker-compose.yml'
$DefaultInstance = 'dev'
$RuntimeRoot = Join-Path $RepoRoot '.runtime'
$RuntimeDir = Join-Path $RuntimeRoot $Instance
$InfraServices = @('mysql', 'redis', 'minio', 'rabbitmq')
$AppServices = @('backend', 'frontend-admin', 'frontend-user')
$ImageServices = @{
    'backend'        = 'chen-stack-backend'
    'frontend-admin' = 'chen-stack-admin'
    'frontend-user'  = 'chen-stack-user'
}
$ServiceContainers = @{
    'mysql'          = 'chen-stack-mysql'
    'redis'          = 'chen-stack-redis'
    'minio'          = 'chen-stack-minio'
    'rabbitmq'       = 'chen-stack-rabbitmq'
    'qdrant'         = 'chen-stack-qdrant'
    'backend'        = 'chen-stack-backend'
    'frontend-admin' = 'chen-stack-admin'
    'frontend-user'  = 'chen-stack-user'
}
$Migrations = @(
    @{ File = 'g2_rag_catalog.sql'; Kind = 'table'; Marker = 'rag_catalog_state' }
    @{ File = 'g3_wiki_foundation.sql'; Kind = 'table'; Marker = 'wiki_source' }
    @{ File = 'g3_wiki_compiler.sql'; Kind = 'table'; Marker = 'wiki_compile_budget' }
    @{ File = 'g3_wiki_invalidation.sql'; Kind = 'trigger'; Marker = 'wiki_article_changed' }
    @{ File = 'g3_wiki_product.sql'; Kind = 'table'; Marker = 'wiki_page' }
    @{ File = 'g3_ai_knowledge_management.sql'; Kind = 'menu'; Marker = '9300' }
    @{ File = 'g3_ai_knowledge_configuration.sql'; Kind = 'table'; Marker = 'ai_runtime_config' }
)

# 诊断与安全恢复引擎（只读 doctor + 受控 repair-ports）。统一入口仍是本脚本。
$DoctorScript = Join-Path $PSScriptRoot 'ChenStackDoctor.ps1'
if (-not (Test-Path -LiteralPath $DoctorScript)) { throw "缺少诊断引擎 $DoctorScript" }
. $DoctorScript

function Write-Title([string]$Text) {
    Write-Host ''
    Write-Host ('=' * 72) -ForegroundColor DarkCyan
    Write-Host "  $Text" -ForegroundColor Cyan
    Write-Host ('=' * 72) -ForegroundColor DarkCyan
}
function Write-Step([string]$Text) { Write-Host "  -> $Text" -ForegroundColor Gray }
function Write-Ok([string]$Text) { Write-Host "  OK  $Text" -ForegroundColor Green }
function Write-Warn2([string]$Text) { Write-Host "  !!  $Text" -ForegroundColor Yellow }
function Write-Fail([string]$Text) { Write-Host "  XX  $Text" -ForegroundColor Red }

function Assert-Command([string]$Name, [string]$Hint) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "缺少命令 '$Name'。$Hint"
    }
}

function Get-EnvPath {
    $path = Join-Path $RuntimeDir '.env'
    if (Test-Path -LiteralPath $path) { return $path }
    $legacy = if ($Instance -eq $DefaultInstance) { Join-Path $ComposeDir '.env' }
              else { Join-Path $ComposeDir ".instances/$Instance/.env" }
    if (Test-Path -LiteralPath $legacy) { return $legacy }
    return $path
}

function Get-OverridePath {
    if ($Instance -eq $DefaultInstance) { return $null }
    return (Join-Path (Split-Path -Parent (Get-EnvPath)) 'override.yml')
}

function Get-ComposeArgs {
    $composeArgs = @('compose', '-p', $Instance, '--project-directory', $ComposeDir, '--env-file', (Get-EnvPath), '-f', $ComposeFile)
    $override = Get-OverridePath
    if ($override -and (Test-Path -LiteralPath $override)) { $composeArgs += @('-f', $override) }
    if ($WithRag) { $composeArgs += @('--profile', 'rag') }
    return $composeArgs
}

function Invoke-Compose([string[]]$Arguments, [switch]$AllowFailure) {
    $all = (Get-ComposeArgs) + $Arguments
    Write-Verbose ('docker ' + ($all -join ' '))
    $useRuntimeDefault = $Action -in 'upgrade', 'rollback' -and -not $env:AI_USAGE_DIRECTORY -and
        -not (Read-DotEnv (Get-EnvPath)).Contains('AI_USAGE_DIRECTORY')
    if ($useRuntimeDefault) { $env:AI_USAGE_DIRECTORY = Join-Path $RuntimeDir 'ai-usage' }
    try {
        $output = & docker @all 2>&1
        $code = $LASTEXITCODE
    } finally {
        if ($useRuntimeDefault) { $env:AI_USAGE_DIRECTORY = $null }
    }
    if ($code -ne 0 -and -not $AllowFailure) {
        Write-Host ($output | Out-String)
        throw "docker compose $($Arguments -join ' ') 失败（exit=$code）"
    }
    return [pscustomobject]@{ ExitCode = $code; Output = ($output | Out-String) }
}

function Invoke-Docker([string[]]$Arguments, [switch]$AllowFailure) {
    $output = & docker @Arguments 2>&1
    $code = $LASTEXITCODE
    if ($code -ne 0 -and -not $AllowFailure) {
        Write-Host ($output | Out-String)
        throw "docker $($Arguments -join ' ') 失败（exit=$code）"
    }
    return [pscustomobject]@{ ExitCode = $code; Output = ($output | Out-String).Trim() }
}

function New-Secret([int]$Bytes = 32) {
    $buffer = [byte[]]::new($Bytes)
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($buffer)
    return [Convert]::ToHexString($buffer).ToLowerInvariant()
}

function Initialize-PrivateRuntime {
    if (Test-Path -LiteralPath $RuntimeRoot) {
        $directory = Get-Item -LiteralPath $RuntimeRoot -Force
        if (-not $directory.PSIsContainer -or $directory.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw '.runtime 必须是当前仓库中的普通目录'
        }
    } else {
        New-Item -ItemType Directory -Path $RuntimeRoot | Out-Null
    }
    if ($IsWindows) {
        $owner = [Security.Principal.WindowsIdentity]::GetCurrent().User
        $directory = [IO.DirectoryInfo]::new($RuntimeRoot)
        $acl = [IO.FileSystemAclExtensions]::GetAccessControl($directory, [Security.AccessControl.AccessControlSections]::Access)
        $acl.SetAccessRuleProtection($true, $false)
        foreach ($rule in $acl.GetAccessRules($true, $false, [Security.Principal.SecurityIdentifier])) {
            $acl.PurgeAccessRules($rule.IdentityReference)
        }
        foreach ($sid in @($owner, [Security.Principal.SecurityIdentifier]::new('S-1-5-18'), [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))) {
            $rule = [Security.AccessControl.FileSystemAccessRule]::new(
                $sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
            $acl.AddAccessRule($rule)
        }
        [IO.FileSystemAclExtensions]::SetAccessControl($directory, $acl)
    } else {
        & chmod 700 -- $RuntimeRoot
        if ($LASTEXITCODE -ne 0) { throw '无法限制 .runtime 的权限' }
    }
    foreach ($path in @($RuntimeDir, (Join-Path $RuntimeDir 'redis'), (Join-Path $RuntimeDir 'ai-usage'),
            (Join-Path $RuntimeDir 'rag'), (Join-Path $RuntimeDir 'rag/control'),
            (Join-Path $RuntimeDir 'rag/budget'), (Join-Path $RuntimeDir 'secrets'))) {
        $existing = Get-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
        if ($existing -and (-not $existing.PSIsContainer -or $existing.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "运行目录不符合要求：$path"
        }
        New-Item -ItemType Directory -Path $path -Force | Out-Null
    }
}

function Read-DotEnv([string]$Path) {
    $map = [ordered]@{}
    if (-not (Test-Path -LiteralPath $Path)) { return $map }
    foreach ($line in Get-Content -LiteralPath $Path -Encoding utf8) {
        $trimmed = $line.Trim()
        if ($trimmed -eq '' -or $trimmed.StartsWith('#')) { continue }
        $index = $trimmed.IndexOf('=')
        if ($index -lt 1) { continue }
        $key = $trimmed.Substring(0, $index).Trim()
        $value = $trimmed.Substring($index + 1).Trim()
        $map[$key] = $value
    }
    return $map
}

function Write-DotEnv([string]$Path, $Map) {
    $lines = foreach ($key in $Map.Keys) { "$key=$($Map[$key])" }
    $directory = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $directory)) { New-Item -ItemType Directory -Path $directory -Force | Out-Null }
    [System.IO.File]::WriteAllText($Path, (($lines -join "`n") + "`n"), [System.Text.UTF8Encoding]::new($false))
}

function Get-ContainerName([string]$Service) {
    $filter = @('ps', '-a', '--filter', "label=com.docker.compose.project=$Instance",
        '--filter', "label=com.docker.compose.service=$Service", '--format', '{{.Names}}')
    $result = Invoke-Docker $filter -AllowFailure
    if ($result.ExitCode -ne 0) { return $null }
    $name = ($result.Output -split "`n" | Where-Object { $_.Trim() -ne '' } | Select-Object -First 1)
    if (-not $name) { return $null }
    return $name.Trim()
}

function Test-ContainerRunning([string]$Name) {
    $result = Invoke-Docker @('inspect', $Name, '--format', '{{.State.Running}}') -AllowFailure
    return ($result.ExitCode -eq 0 -and $result.Output -eq 'true')
}

function Get-ContainerImageId([string]$Name) {
    $result = Invoke-Docker @('inspect', $Name, '--format', '{{.Image}}') -AllowFailure
    if ($result.ExitCode -ne 0) { return $null }
    return $result.Output
}

function Get-BackendHealth([string]$Name) {
    $result = Invoke-Docker @('exec', $Name, 'wget', '-q', '-O-', 'http://127.0.0.1:5000/actuator/health') -AllowFailure
    if ($result.ExitCode -ne 0) { return $null }
    if ($result.Output -match '"status"\s*:\s*"UP"') { return 'UP' }
    return 'DOWN'
}

function Wait-Until([scriptblock]$Probe, [string]$Label, [int]$TimeoutSeconds = 240, [int]$IntervalSeconds = 5) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (& $Probe) { Write-Ok $Label; return $true }
        Start-Sleep -Seconds $IntervalSeconds
    }
    return $false
}

function Assert-NotReleaseManaged([string]$Container) {
    if (-not $Container) { return }
    $labels = (Invoke-Docker @('inspect', $Container, '--format', '{{json .Config.Labels}}') -AllowFailure).Output
    $mounts = (Invoke-Docker @('inspect', $Container, '--format', '{{range .Mounts}}{{println .Destination}}{{end}}') -AllowFailure).Output
    $env = (Invoke-Docker @('inspect', $Container, '--format', '{{range .Config.Env}}{{println .}}{{end}}') -AllowFailure).Output
    $releaseMount = @($mounts -split "`n" | Where-Object { $_.Trim() -eq '/release' }).Count -gt 0
    $releaseLabel = $labels -match 'chen\.release'
    $releaseRecovery = $env -match 'CHEN_RAG_RELEASE_RECOVERY_ENABLED=true'
    if ($releaseMount -or $releaseLabel -or $releaseRecovery) {
        throw @"
检测到该实例由受控发布流程管理（script/release），不能使用本脚本的 upgrade/rollback。

原因：正式 RAG 读取权限由“发布清单 + 已完成回执”授予，清单绑定运行 JAR 的 SHA256；
用普通 Compose 重建容器会让运行制品与发布清单不一致，后端会 fail-closed 拒绝启动。

正确做法：按 script/release/README.md 的受控发布流程升级（重新生成 RECOVER 清单与操作记录后重建容器）。
"@
    }
}

# ---------------------------------------------------------------- 环境与凭据

function Initialize-Environment {
    $envPath = Get-EnvPath
    $templatePath = Join-Path $ComposeDir '.env.example'
    if (-not (Test-Path -LiteralPath $templatePath)) { throw "缺少模板 script/dev/.env.example" }

    if (Test-Path -LiteralPath $envPath) {
        Write-Warn2 "已存在 $(Split-Path -Leaf $envPath)，保留现有配置（不覆盖）"
    } else {
        Copy-Item -LiteralPath $templatePath -Destination $envPath
        Write-Ok "已生成 $envPath"
    }

    $map = Read-DotEnv $envPath
    $generated = @()
    $secretKeys = @{
        'MYSQL_ROOT_PASSWORD'      = 32
        'MYSQL_PASSWORD'           = 32
        'REDIS_PASSWORD'           = 32
        'MINIO_SECRET_KEY'         = 32
        'RABBITMQ_DEFAULT_PASS'    = 24
        'CHEN_STACK_JWT_SECRET'    = 48
        'CHEN_RAG_QDRANT_API_KEY'  = 24
    }
    foreach ($key in $secretKeys.Keys) {
        $current = if ($map.Contains($key)) { [string]$map[$key] } else { '' }
        if ([string]::IsNullOrWhiteSpace($current)) {
            $map[$key] = New-Secret $secretKeys[$key]
            $generated += $key
        }
    }
    if ($Instance -ne $DefaultInstance -and $PortBase) {
        $ports = @('MYSQL_PORT', 'REDIS_PORT', 'MINIO_API_PORT', 'MINIO_CONSOLE_PORT',
            'RABBITMQ_PORT', 'RABBITMQ_MANAGEMENT_PORT', 'BACKEND_PORT', 'ADMIN_PORT', 'USER_PORT',
            'CHEN_RAG_QDRANT_HTTP_PORT', 'CHEN_LOCAL_QDRANT_GRPC_PORT')
        for ($i = 0; $i -lt $ports.Count; $i++) { $map[$ports[$i]] = [string]($PortBase + $i) }
        $map['MINIO_PUBLIC_POINT'] = "http://127.0.0.1:$($map['MINIO_API_PORT'])"
        $map['FRONTEND_ADMIN_HOST'] = "http://127.0.0.1:$($map['ADMIN_PORT'])/"
        $map['FRONTEND_USER_HOST'] = "http://127.0.0.1:$($map['USER_PORT'])/"
    }
    if ($WithRag) {
        if ($map['CHEN_RAG_EMBEDDING_API_KEY']) {
            $map['SPRING_PROFILES_ACTIVE'] = 'dev,rag'
            $map['CHEN_RAG_ENABLED'] = 'true'
        }
        $map['CHEN_RAG_QDRANT_HOST'] = 'qdrant'
        $map['CHEN_RAG_QDRANT_TLS'] = 'false'
        $map['CHEN_RAG_QDRANT_GRPC_PORT'] = '6334'
        Write-Warn2 '本地 Qdrant 已选用；RAG 需要 Embedding 凭据与一次受控知识发布'
    }
    if ($map.Contains('SPRING_AI_OPENAI_API_KEY') -and [string]::IsNullOrWhiteSpace([string]$map['SPRING_AI_OPENAI_API_KEY'])) {
        Write-Warn2 'SPRING_AI_OPENAI_API_KEY 为空：小辰会返回模型服务错误，请在 .env 中填入 DeepSeek Key'
    }

    $redisDir = Join-Path $RuntimeDir 'redis'
    $map['REDIS_SECURE_CONFIG_DIRECTORY'] = $redisDir
    $map['REDIS_HEALTH_PASSWORD_FILE'] = (Join-Path $redisDir 'redis-app.secret')
    $map['AI_USAGE_DIRECTORY'] = Join-Path $RuntimeDir 'ai-usage'
    Write-DotEnv $envPath $map
    if ($generated.Count -gt 0) { Write-Ok ("已生成随机口令：" + ($generated -join ', ')) }

    if (-not (Test-Path -LiteralPath $redisDir)) { New-Item -ItemType Directory -Path $redisDir -Force | Out-Null }
    $redisUser = if ($map.Contains('REDIS_USERNAME') -and $map['REDIS_USERNAME']) { [string]$map['REDIS_USERNAME'] } else { 'chenstack' }
    $redisPassword = [string]$map['REDIS_PASSWORD']
    $confPath = Join-Path $redisDir 'redis.conf'
    $aclPath = Join-Path $redisDir 'users.acl'
    $secretPath = Join-Path $redisDir 'redis-app.secret'
    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
    if (-not (Test-Path -LiteralPath $confPath)) {
        $conf = @(
            'bind 0.0.0.0'
            'protected-mode yes'
            'port 6379'
            'dir /data'
            'appendonly yes'
            'aclfile /secure/users.acl'
        ) -join "`n"
        [System.IO.File]::WriteAllText($confPath, $conf + "`n", $utf8NoBom)
        Write-Ok "已生成 redis.conf（$redisDir）"
    }
    # ACL 文件必须是 LF 换行：Windows 的 CRLF 会让 Redis 把 \r 当成规则字符而拒绝整行。
    # 同时校验口令与当前 .env 一致，避免沿用旧口令造成健康检查 AUTH 失败。
    $expectedAclLine = "user $redisUser on >$redisPassword ~* &* +@all"
    $aclContent = if (Test-Path -LiteralPath $aclPath) { [System.IO.File]::ReadAllText($aclPath) } else { '' }
    if ($aclContent.IndexOf($expectedAclLine, [StringComparison]::Ordinal) -lt 0) {
        $acl = @('user default off', $expectedAclLine) -join "`n"
        [System.IO.File]::WriteAllText($aclPath, $acl + "`n", $utf8NoBom)
        Write-Ok "已生成 users.acl（用户 $redisUser，LF 换行）"
    }
    $secretContent = if (Test-Path -LiteralPath $secretPath) { ([System.IO.File]::ReadAllText($secretPath)).TrimEnd("`r", "`n") } else { '' }
    if ($secretContent -cne $redisPassword) {
        [System.IO.File]::WriteAllText($secretPath, $redisPassword + "`n", $utf8NoBom)
        Write-Ok '已生成 redis-app.secret'
    }
    return $map
}

function New-InstanceOverride([System.Collections.IDictionary]$EnvMap) {
    $override = Get-OverridePath
    if (-not $override) { return }
    $directory = Split-Path -Parent $override
    if (-not (Test-Path -LiteralPath $directory)) { New-Item -ItemType Directory -Path $directory -Force | Out-Null }
    $lines = @('services:')
    foreach ($service in $ServiceContainers.Keys) {
        $lines += "  ${service}:"
        $lines += "    container_name: $Instance-$service"
        if ($service -eq 'backend') {
            $origins = @($EnvMap['FRONTEND_ADMIN_HOST'].TrimEnd('/'), $EnvMap['FRONTEND_USER_HOST'].TrimEnd('/'))
            $springJson = @{ 'chen-stack' = @{ 'allow-origins' = $origins } } | ConvertTo-Json -Depth 4 -Compress
            $lines += '    environment:'
            $lines += "      SPRING_APPLICATION_JSON: '$springJson'"
        }
        if ($ImageServices.ContainsKey($service)) {
            $lines += "    image: $($ImageServices[$service])-$Instance`:$Instance"
        }
    }
    $lines += 'volumes:'
    foreach ($volume in @('mysql_data', 'redis_data', 'minio_data', 'rabbitmq_data', 'qdrant_data')) {
        $lines += "  ${volume}:"
        $lines += "    name: $Instance-$($volume -replace '_', '-')"
    }
    $lines += 'networks:'
    $lines += '  chen-stack-network:'
    $lines += "    name: $Instance-network"
    Set-Content -LiteralPath $override -Value $lines -Encoding utf8NoBOM
    Write-Ok "已生成隔离覆盖文件 $override"
}

function Assert-FreshCompose($EnvMap) {
    $result = Invoke-Compose @('config', '--format', 'json') -AllowFailure
    if ($result.ExitCode -ne 0) { throw '初始化 Compose 配置无法解析' }
    $model = $result.Output | ConvertFrom-Json -AsHashtable
    if ($model.name -cne $Instance) { throw 'Compose 项目名不符' }
    foreach ($volume in $model.volumes.Values) {
        if (($Instance -ne $DefaultInstance -and $volume.name -notlike "$Instance-*") -or
            (Invoke-Docker @('volume', 'inspect', $volume.name) -AllowFailure).ExitCode -eq 0) {
            throw "初始化要求独占新数据卷：$($volume.name)"
        }
    }
    foreach ($network in $model.networks.Values) {
        if (($Instance -ne $DefaultInstance -and $network.name -cne "$Instance-network") -or
            (Invoke-Docker @('network', 'inspect', $network.name) -AllowFailure).ExitCode -eq 0) {
            throw "初始化要求独占新网络：$($network.name)"
        }
    }
    foreach ($key in $model.services.Keys) {
        $service = $model.services[$key]
        if ($Instance -ne $DefaultInstance -and $service.container_name -cne "$Instance-$key") {
            throw "隔离容器名不符：$key"
        }
        if ((Invoke-Docker @('container', 'inspect', $service.container_name) -AllowFailure).ExitCode -eq 0) {
            throw "已有容器：$($service.container_name)"
        }
        foreach ($mount in $service['volumes']) {
            if ($mount['type'] -ne 'bind') { continue }
            $source = [IO.Path]::GetFullPath($mount.source)
            if (-not $source.StartsWith(($RepoRoot + [IO.Path]::DirectorySeparatorChar), [StringComparison]::OrdinalIgnoreCase)) {
                throw "初始化绑定不在仓库内：$key/$($mount.target)"
            }
        }
    }
    $credentials = @{
        mysql = @('MYSQL_ROOT_PASSWORD', 'MYSQL_PASSWORD')
        backend = @('MYSQL_PASSWORD', 'REDIS_PASSWORD', 'MINIO_SECRET_KEY', 'CHEN_STACK_JWT_SECRET')
        rabbitmq = @('RABBITMQ_DEFAULT_PASS')
    }
    foreach ($service in $credentials.Keys) {
        foreach ($key in $credentials[$service]) {
            if ($model.services[$service].environment[$key] -cne $EnvMap[$key]) {
                throw "外部环境覆盖实例凭据：$key"
            }
        }
    }
    if ($Instance -ne $DefaultInstance) {
        $published = @($model.services.Values | ForEach-Object { $_['ports'] } |
                Where-Object { $_ } | ForEach-Object {
                    if ($_['host_ip'] -cne '127.0.0.1') { throw '隔离端口必须绑定 loopback' }
                    [int]$_['published']
                } | Sort-Object)
        $lastOffset = if ($WithRag) { 10 } else { 8 }
        if (($published -join ',') -cne (($PortBase..($PortBase + $lastOffset)) -join ',')) {
            throw '隔离端口被外部配置覆盖'
        }
    }
    Write-Ok 'Compose 项目、容器、网络、卷和宿主挂载校验通过'
}

# ---------------------------------------------------------------- 构建

function Build-HostArtifacts {
    if ($SkipBuild) { Write-Warn2 '已跳过宿主机产物构建（-SkipBuild）'; return }
    $jar = Join-Path $RepoRoot 'chen-stack-backend/target/zcx-chen-stack-backend-1.0-SNAPSHOT.jar'
    if ($Action -ne 'upgrade' -and (Test-Path -LiteralPath $jar)) {
        Write-Ok '后端 JAR 已存在，复用现有产物'
    } else {
        Assert-Command 'mvn' '构建后端需要 JDK 21 + Maven 3.9+，或先在本地执行 mvn package'
        Write-Step '构建后端 JAR（跳过测试）'
        Push-Location (Join-Path $RepoRoot 'chen-stack-backend')
        try {
            & mvn --batch-mode '-Dmaven.test.skip=true' package
            if ($LASTEXITCODE -ne 0) { throw '后端构建失败' }
        } finally { Pop-Location }
    }
    Assert-Command 'npm' '构建前端需要 Node.js 22.12+ 与 npm'
    $previousBackend = $env:VITE_BACKEND_SERVER
    if ($Instance -ne $DefaultInstance) {
        $envMap = Read-DotEnv (Get-EnvPath)
        $backendPort = [int]$envMap['BACKEND_PORT']
        if ($backendPort -lt 1 -or $backendPort -gt 65535) { throw '隔离实例 BACKEND_PORT 无效' }
        $env:VITE_BACKEND_SERVER = "http://127.0.0.1:$backendPort"
    }
    try {
        # dist 内嵌 API 地址，同一工作区内的其他实例可能已覆盖旧产物。
        foreach ($name in @('chen-stack-admin', 'chen-stack-user')) {
            Write-Step "构建 $name"
            Push-Location (Join-Path $RepoRoot "chen-stack-frontend/$name")
            try {
                if (-not (Test-Path -LiteralPath 'node_modules')) {
                    & npm ci --no-audit --no-fund
                    if ($LASTEXITCODE -ne 0) { throw "$name 依赖安装失败" }
                }
                & npm run build
                if ($LASTEXITCODE -ne 0) { throw "$name 构建失败" }
            } finally { Pop-Location }
        }
    } finally {
        $env:VITE_BACKEND_SERVER = $previousBackend
    }
}

function Build-Images {
    if ($SkipBuild) { Write-Warn2 '已跳过镜像构建（-SkipBuild）'; return }
    Write-Step '构建后端 / 管理端 / 用户端镜像'
    $result = Invoke-Compose @('build', 'backend', 'frontend-admin', 'frontend-user')
    Write-Verbose $result.Output
    Write-Ok '镜像构建完成'
}

function Initialize-AdminAccount {
    $mysql = Get-ContainerName 'mysql'
    $image = $ImageServices['backend']
    if ($Instance -ne $DefaultInstance) { $image = "${image}-$Instance`:$Instance" }
    $password = New-Secret 8
    $jshellInput = "System.out.println(""HASH="" + org.springframework.security.crypto.bcrypt.BCrypt.hashpw(""$password"", org.springframework.security.crypto.bcrypt.BCrypt.gensalt()));" + [char]10 + '/exit'
    $hashScript = 'set -eu; cd "$(mktemp -d)"; entry=$(jar tf /app/app.jar | grep -E "^BOOT-INF/lib/spring-security-crypto-[^/]+\.jar$"); jar xf /app/app.jar "$entry"; exec jshell --feedback silent --class-path "$entry" -'
    $output = @($jshellInput | & docker run -i --rm --pull never --network none --entrypoint sh $image -c $hashScript 2>$null)
    if ($LASTEXITCODE) { throw '无法为新实例生成管理端密码' }
    $hash = @($output | ForEach-Object {
        if ($_ -match 'HASH=(\$2[aby]\$[0-9]{2}\$[A-Za-z0-9./]{53})') { $Matches[1] }
    })
    if ($hash.Count -ne 1) { throw '新实例管理端密码的哈希结果无效' }
    $path = Join-Path $RuntimeDir 'secrets/initial-admin.env'
    [IO.File]::WriteAllText($path, "USERNAME=chenstack`nPASSWORD=$password`n", [Text.UTF8Encoding]::new($false))
    $sql = "UPDATE sys_user SET password='$($hash[0])' WHERE id=1 AND username='chenstack'; SELECT ROW_COUNT();"
    $write = Invoke-Docker @('exec', $mysql, 'sh', '-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -D "$MYSQL_DATABASE" -N -B -e "$1"', 'sh', $sql) -AllowFailure
    if ($write.ExitCode -ne 0 -or $write.Output -cne '1') { throw '无法初始化新实例的管理端账号' }
    Write-Ok "首次管理端登录凭据已保存到 $path"
}

# ---------------------------------------------------------------- 服务与迁移

function Start-Services([string[]]$Services) {
    foreach ($service in $Services) {
        $name = Get-ContainerName $service
        if (-not $name) { throw "实例 $Instance 缺少容器 $service，请先执行 -Action init" }
        if (-not (Test-ContainerRunning $name)) {
            Invoke-Docker @('start', $name) | Out-Null
            Write-Step "已启动 $name"
        }
    }
}

function Wait-Infrastructure {
    $mysql = Get-ContainerName 'mysql'
    if (-not $mysql) { throw '缺少 MySQL 容器' }
    if (-not (Wait-Until -Label 'MySQL 就绪' -TimeoutSeconds 300 -Probe {
                (Invoke-Docker @('exec', $mysql, 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=TCP -h127.0.0.1 -uroot -N -B -e "SELECT 1"') -AllowFailure).Output -eq '1'
            })) { throw 'MySQL 未在超时时间内就绪' }

    $redis = Get-ContainerName 'redis'
    if ($redis) {
        if (-not (Wait-Until -Label 'Redis 就绪' -TimeoutSeconds 120 -Probe {
                (Invoke-Docker @('exec', $redis, 'sh', '-c', 'REDISCLI_AUTH="$(cat /run/secrets/redis-health-password)" redis-cli --no-auth-warning --user "$REDIS_USERNAME" ping') -AllowFailure).Output -match 'PONG'
            })) { throw 'Redis 未就绪' }
    }
    $minio = Get-ContainerName 'minio'
    if ($minio) {
        if (-not (Wait-Until -Label 'MinIO 就绪' -TimeoutSeconds 120 -Probe {
                (Invoke-Docker @('exec', $minio, 'curl', '-fsS', 'http://localhost:9000/minio/health/live') -AllowFailure).ExitCode -eq 0
            })) { throw 'MinIO 未就绪' }
    }
    $rabbit = Get-ContainerName 'rabbitmq'
    if ($rabbit) {
        if (-not (Wait-Until -Label 'RabbitMQ 就绪' -TimeoutSeconds 180 -Probe {
                (Invoke-Docker @('exec', $rabbit, 'rabbitmq-diagnostics', '-q', 'ping') -AllowFailure).Output -match 'Ping succeeded'
            })) { throw 'RabbitMQ 未就绪' }
    }
    $qdrant = Get-ContainerName 'qdrant'
    if ($qdrant) {
        if (-not (Wait-Until -Label 'Qdrant 就绪' -TimeoutSeconds 120 -Probe {
                    (Invoke-Docker @('exec', $qdrant, 'bash', '-ec', 'exec 3<>/dev/tcp/127.0.0.1/6333; printf "GET /readyz HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3; head -n 1 <&3 | grep -q 200') -AllowFailure).ExitCode -eq 0
                })) { throw 'Qdrant 未就绪' }
    }
}

function Invoke-Migrations {
    $envMap = Read-DotEnv (Get-EnvPath)
    $database = if ($envMap.Contains('MYSQL_DATABASE') -and $envMap['MYSQL_DATABASE']) { [string]$envMap['MYSQL_DATABASE'] } else { 'chen_stack' }
    $mysql = Get-ContainerName 'mysql'
    if (-not $mysql) { throw '缺少 MySQL 容器，无法执行迁移' }
    foreach ($migration in $Migrations) {
        $file = Join-Path $RepoRoot "sql/$($migration.File)"
        if (-not (Test-Path -LiteralPath $file)) { throw "缺少迁移文件 sql/$($migration.File)" }
        $query = switch ($migration.Kind) {
            'table' { "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$database' AND table_name='$($migration.Marker)'" }
            'trigger' { "SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema='$database' AND trigger_name='$($migration.Marker)'" }
            'menu' { "SELECT COUNT(*) FROM $database.sys_menu WHERE id=$($migration.Marker)" }
            default { throw "未知的迁移标记类型 $($migration.Kind)" }
        }
        $probe = Invoke-Docker @('exec', $mysql, 'sh', '-c', "MYSQL_PWD=`"`$MYSQL_ROOT_PASSWORD`" mysql -uroot -N -B -e `"$query`"") -AllowFailure
        if ($probe.ExitCode -ne 0) { throw "检查迁移 $($migration.File) 状态失败：$($probe.Output)" }
        if ($probe.Output.Trim() -ne '0') {
            Write-Verbose "迁移 $($migration.File) 已应用"
            continue
        }
        Write-Step "应用迁移 sql/$($migration.File)"
        $sql = Get-Content -LiteralPath $file -Raw -Encoding utf8
        $sql | & docker exec -i $mysql sh -c "MYSQL_PWD=`"`$MYSQL_ROOT_PASSWORD`" exec mysql -uroot --default-character-set=utf8mb4 $database"
        if ($LASTEXITCODE -ne 0) { throw "迁移 sql/$($migration.File) 执行失败" }
    }
    Write-Ok '数据库迁移检查完成'
}

function Wait-Applications {
    $backend = Get-ContainerName 'backend'
    if (-not $backend) { throw '缺少 backend 容器' }
    if (-not (Wait-Until -Label '后端健康检查通过' -TimeoutSeconds 300 -Probe {
                (Get-BackendHealth $backend) -eq 'UP'
            })) {
        Write-Fail '后端未在超时时间内健康，最近日志：'
        Write-Host (Invoke-Docker @('logs', '--tail', '60', $backend) -AllowFailure).Output
        throw '后端启动失败'
    }
    foreach ($service in @('frontend-admin', 'frontend-user')) {
        $name = Get-ContainerName $service
        if (-not $name) { throw "缺少前端容器：$service" }
        if (-not (Wait-Until -Label "$service 可访问" -TimeoutSeconds 120 -Probe {
                (Invoke-Docker @('exec', $name, 'curl', '-fsS', '-o', '/dev/null', 'http://127.0.0.1/') -AllowFailure).ExitCode -eq 0
            })) { throw "前端未就绪：$service" }
    }
}

function Show-Access([string]$EnvPath) {
    $map = Read-DotEnv $EnvPath
    $backendPort = if ($map.Contains('BACKEND_PORT') -and $map['BACKEND_PORT']) { $map['BACKEND_PORT'] } else { '5000' }
    $adminPort = if ($map.Contains('ADMIN_PORT') -and $map['ADMIN_PORT']) { $map['ADMIN_PORT'] } else { '8000' }
    $userPort = if ($map.Contains('USER_PORT') -and $map['USER_PORT']) { $map['USER_PORT'] } else { '7000' }
    $minioConsole = if ($map.Contains('MINIO_CONSOLE_PORT') -and $map['MINIO_CONSOLE_PORT']) { $map['MINIO_CONSOLE_PORT'] } else { '9001' }
    $rabbitConsole = if ($map.Contains('RABBITMQ_MANAGEMENT_PORT') -and $map['RABBITMQ_MANAGEMENT_PORT']) { $map['RABBITMQ_MANAGEMENT_PORT'] } else { '15672' }
    Write-Title '访问入口'
    Write-Host "  管理端      http://127.0.0.1:$adminPort"
    Write-Host "  用户端      http://127.0.0.1:$userPort"
    Write-Host "  后端 API    http://127.0.0.1:$backendPort"
    Write-Host "  MinIO 控制台 http://127.0.0.1:$minioConsole"
    Write-Host "  RabbitMQ    http://127.0.0.1:$rabbitConsole"
    Write-Host ''
    Write-Host ('  实例名：' + $Instance) -ForegroundColor DarkGray
}

function Show-Status {
    param([switch]$WithDoctor)
    Write-Title "辰栈实例状态（$Instance）"
    $rows = @()
    foreach ($service in ($InfraServices + @('qdrant') + $AppServices)) {
        $name = Get-ContainerName $service
        if (-not $name) { $rows += [pscustomobject]@{ 服务 = $service; 容器 = '-'; 状态 = '未创建' }; continue }
        $inspect = Invoke-Docker @('inspect', $name, '--format', '{{.State.Status}}|{{.State.StartedAt}}|{{.Image}}') -AllowFailure
        $parts = $inspect.Output.Split('|')
        $imageShort = ''
        if ($inspect.Output -match 'sha256:([0-9a-f]{12})') { $imageShort = $Matches[1] }
        $rows += [pscustomobject]@{ 服务 = $service; 容器 = $name; 状态 = $parts[0]; 镜像 = $imageShort }
    }
    $rows | Format-Table -AutoSize | Out-String | Write-Host
    $backend = Get-ContainerName 'backend'
    if ($backend -and (Test-ContainerRunning $backend)) {
        Write-Host ('  后端健康：' + (Get-BackendHealth $backend))
    }
    $envPath = Get-EnvPath
    if (Test-Path -LiteralPath $envPath) {
        $map = Read-DotEnv $envPath
        $mode = if ($map.Contains('AI_KNOWLEDGE_MODE')) { [string]$map['AI_KNOWLEDGE_MODE'] } else { '未设置' }
        Write-Host ('  部署默认知识模式(AI_KNOWLEDGE_MODE)：' + $mode + '（实际生效以数据库配置为准）')
    }
    if (-not $WithDoctor) { return }

    # 容器状态不等于部署可用：status 也展示端口发布与宿主机访问结果。
    $doctor = Invoke-ChenStackDoctor -Context (New-InstanceDoctorContext)
    Write-Host ''
    Write-Host '  宿主机连通性摘要' -ForegroundColor Cyan
    foreach ($line in $doctor.Summary) { Write-Host "    $line" }
    Write-Host ''
    Write-Host '  完整诊断：pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action doctor' -ForegroundColor DarkGray
}

# ---------------------------------------------------------------- 宿主机连通性

function New-InstanceDoctorContext {
    $envPath = Get-EnvPath
    $map = Read-DotEnv $envPath
    return New-ChenStackDoctorContext -RepoRoot $RepoRoot -Instance $Instance -EnvMap $map -EnvPath $envPath
}

function Assert-HostReachability {
    <#
    .SYNOPSIS
        启动/升级/重启后的硬性验收：容器运行、端口实际发布、宿主机 HTTP 可达。
    .DESCRIPTION
        Docker Running 或容器内健康 UP 都不足以判定部署成功。任何一项失败都会打印完整诊断
        并终止当前操作，避免把“容器在跑但 API 不通”当成成功。
    #>
    param([string]$Stage = '部署')

    $context = New-InstanceDoctorContext
    $result = Invoke-ChenStackDoctor -Context $context
    if ($result.Ok) {
        Write-Ok "宿主机连通性检查通过：端口发布存在，后端与两个前端均可从 127.0.0.1 访问"
        return $result
    }

    Format-ChenStackDoctorReport -Result $result
    Write-Host '  摘要' -ForegroundColor Cyan
    foreach ($line in $result.Summary) { Write-Host "    $line" }
    Write-Host ''
    throw "$Stage 校验未通过：ROOT_CAUSE=$($result.RootCause)。修复建议：$($result.RecommendedAction)"
}

function Invoke-DoctorAction {
    $context = New-InstanceDoctorContext
    $result = Invoke-ChenStackDoctor -Context $context
    Format-ChenStackDoctorReport -Result $result

    $clipboard = $null
    if (-not $NoClipboard) { $clipboard = Set-ChenStackDoctorClipboard -Lines $result.Summary }
    if ($NoClipboard) {
        Write-Host '  摘要（未复制到剪贴板）' -ForegroundColor Cyan
    } elseif ($clipboard.Status -eq 'COPIED_VERIFIED') {
        Write-Host '  摘要（已复制到剪贴板）' -ForegroundColor Cyan
    } else {
        Write-Host '  摘要（已生成，剪贴板复制未确认）' -ForegroundColor Cyan
    }
    foreach ($line in $result.Summary) { Write-Host "    $line" }
    Write-Host ''
    if ($NoClipboard) {
        Write-Host '  CLIPBOARD=SKIPPED（-NoClipboard）' -ForegroundColor DarkGray
    } else {
        Write-Host "  CLIPBOARD=$($clipboard.Status) $($clipboard.Detail)" -ForegroundColor DarkGray
    }
    if (-not $result.Ok) {
        Write-Host ''
        Write-Fail "诊断未通过：ROOT_CAUSE=$($result.RootCause)"
    }
    return $result
}

function Invoke-RepairPortsAction {
    $context = New-InstanceDoctorContext
    $repair = Invoke-ChenStackPortRepair -Context $context -Apply:$Apply `
        -AllowReleaseRestart:$AllowReleaseRestart -MaxAttempts $MaxAttempts

    Format-ChenStackRepairReport -Repair $repair
    $doctorForReport = if ($null -ne $repair.Post) { $repair.Post } else { $repair.Doctor }
    if ($null -ne $doctorForReport) { Format-ChenStackDoctorReport -Result $doctorForReport }

    Write-Host '  摘要' -ForegroundColor Cyan
    foreach ($line in $repair.Summary) { Write-Host "    $line" }
    Write-Host ''
    if ($NoClipboard) {
        Write-Host '  CLIPBOARD=SKIPPED（-NoClipboard）' -ForegroundColor DarkGray
    } else {
        $clipboard = Set-ChenStackDoctorClipboard -Lines $repair.Summary
        Write-Host "  CLIPBOARD=$($clipboard.Status) $($clipboard.Detail)" -ForegroundColor DarkGray
    }
    return $repair
}

# ---------------------------------------------------------------- 动作

function Invoke-Init {
    Write-Title "首次全新部署（实例 $Instance）"
    Assert-Command 'docker' '请先安装 Docker Desktop'
    [void](Invoke-Docker @('version', '--format', '{{.Server.Version}}'))
    $existing = Invoke-Docker @('ps', '-aq', '--filter', "label=com.docker.compose.project=$Instance")
    if ($existing.Output) {
        throw "实例 $Instance 已有容器；请使用 up 或按已有数据流程升级"
    }
    if ($Instance -ne $DefaultInstance -and -not $PortBase) {
        throw '首次创建隔离实例需指定 -PortBase'
    }
    if ($Instance -ne $DefaultInstance) {
        $ports = $PortBase..($PortBase + 10)
        $listeners = [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
        if (@($listeners | Where-Object { $_.Port -in $ports }).Count) { throw '隔离端口范围已被占用' }
    }
    Initialize-PrivateRuntime
    $envMap = Initialize-Environment
    New-InstanceOverride $envMap
    Assert-FreshCompose $envMap
    Build-HostArtifacts
    Build-Images

    Write-Step '启动基础服务'
    $services = if ($WithRag) { $InfraServices + 'qdrant' } else { $InfraServices }
    [void](Invoke-Compose (@('up', '-d', '--no-build') + $services))
    Wait-Infrastructure
    Invoke-Migrations
    Initialize-AdminAccount

    Write-Step '启动应用服务'
    [void](Invoke-Compose (@('up', '-d', '--no-build', '--pull', 'never') + $AppServices))
    Wait-Applications
    [void](Assert-HostReachability -Stage '首次部署')
    Show-Access (Get-EnvPath)
    Write-Host ''
    Write-Host '  首次登录后请修改管理端密码并删除初始凭据文件。' -ForegroundColor Yellow
    Write-Host '  小辰知识问答需要先准备知识：管理端「AI知识管理 → 整理知识」可手动新建并发布 Wiki 知识（无需 Embedding）。' -ForegroundColor Yellow
}

function Invoke-Up {
    Write-Title "启动已有实例（$Instance）"
    $missing = @()
    $stopped = @()
    foreach ($service in ($InfraServices + @('qdrant') + $AppServices)) {
        $name = Get-ContainerName $service
        if (-not $name) { continue }
        if (-not (Test-ContainerRunning $name)) { $stopped += $name }
    }
    if ($stopped.Count -eq 0 -and -not (Get-ContainerName 'backend')) {
        throw "实例 $Instance 尚未初始化，请先执行 -Action init"
    }
    if ($stopped.Count -eq 0) {
        Write-Ok '全部容器已在运行'
    } else {
        foreach ($name in $stopped) {
            Invoke-Docker @('start', $name) | Out-Null
            Write-Step "已启动 $name"
        }
    }
    Wait-Infrastructure
    Wait-Applications
    [void](Assert-HostReachability -Stage '日常启动')
    Show-Status
}

function Invoke-Stop {
    Write-Title "停止实例容器（保留数据卷）"
    $names = @()
    foreach ($service in ($InfraServices + @('qdrant') + $AppServices)) {
        $name = Get-ContainerName $service
        if ($name -and (Test-ContainerRunning $name)) { $names += $name }
    }
    if ($names.Count -eq 0) { Write-Ok '没有运行中的容器'; return }
    [void](Invoke-Docker (@('stop') + $names))
    Write-Ok "已停止 $($names.Count) 个容器；数据卷保留，执行 -Action up 可恢复"
}

function Invoke-Restart {
    Write-Title '重启应用容器'
    foreach ($service in $AppServices) {
        $name = Get-ContainerName $service
        if (-not $name) { continue }
        [void](Invoke-Docker @('restart', $name))
        Write-Step "已重启 $name"
    }
    Wait-Applications
    [void](Assert-HostReachability -Stage '应用重启')
    Show-Status
}

function Invoke-Upgrade {
    Write-Title "升级已有实例（$Instance）"
    $backend = Get-ContainerName 'backend'
    if (-not $backend) { throw "实例 $Instance 尚未初始化，请先执行 -Action init" }
    Assert-NotReleaseManaged $backend
    Initialize-PrivateRuntime

    Write-Step '记录当前应用镜像，供回滚使用'
    foreach ($service in $ImageServices.Keys) {
        $name = Get-ContainerName $service
        if (-not $name) { continue }
        $imageId = Get-ContainerImageId $name
        $tag = "$($ImageServices[$service]):previous"
        if ($Instance -ne $DefaultInstance) { $tag = "$($ImageServices[$service])-$Instance`:previous" }
        [void](Invoke-Docker @('tag', $imageId, $tag))
        Write-Step "已保存 $tag"
    }

    Build-HostArtifacts
    Build-Images
    Invoke-Migrations

    Write-Step '重建应用容器（基础服务与数据卷不变）'
    [void](Invoke-Compose (@('up', '-d', '--no-deps', '--no-build', '--pull', 'never') + $AppServices))
    Wait-Applications
    [void](Assert-HostReachability -Stage '升级')
    Write-Ok '升级完成；如需回滚执行 -Action rollback'
}

function Invoke-Rollback {
    Write-Title "回滚应用镜像（$Instance）"
    $backend = Get-ContainerName 'backend'
    if (-not $backend) { throw "实例 $Instance 尚未初始化" }
    Assert-NotReleaseManaged $backend
    Initialize-PrivateRuntime
    foreach ($service in $ImageServices.Keys) {
        $tag = "$($ImageServices[$service]):previous"
        if ($Instance -ne $DefaultInstance) { $tag = "$($ImageServices[$service])-$Instance`:previous" }
        $probe = Invoke-Docker @('image', 'inspect', $tag, '--format', '{{.Id}}') -AllowFailure
        if ($probe.ExitCode -ne 0) { throw "找不到回滚镜像 $tag，请先执行过 upgrade" }
        $target = "$($ImageServices[$service]):latest"
        if ($Instance -ne $DefaultInstance) { $target = "$($ImageServices[$service])-$Instance`:$Instance" }
        [void](Invoke-Docker @('tag', $probe.Output, $target))
        Write-Step "已把 $target 指回 $tag"
    }
    [void](Invoke-Compose (@('up', '-d', '--no-deps', '--no-build', '--pull', 'never') + $AppServices))
    Wait-Applications
    [void](Assert-HostReachability -Stage '回滚')
    Write-Ok '回滚完成'
}

# ---------------------------------------------------------------- 入口

$failed = $false
try {
    switch ($Action) {
        'init' { Invoke-Init }
        'up' { Invoke-Up }
        'stop' { Invoke-Stop }
        'restart' { Invoke-Restart }
        'upgrade' { Invoke-Upgrade }
        'rollback' { Invoke-Rollback }
        'migrate' {
            Write-Title '执行增量 SQL 迁移'
            Invoke-Migrations
        }
        'status' { Show-Status -WithDoctor }
        'doctor' {
            $doctorResult = Invoke-DoctorAction
            if (-not $doctorResult.Ok) { $failed = $true }
        }
        'repair-ports' {
            $repairResult = Invoke-RepairPortsAction
            if ($repairResult.Outcome -in @('FAILED', 'REFUSED')) { $failed = $true }
        }
    }
    if ($failed) { exit 1 }
    if ($Action -notin @('doctor', 'repair-ports', 'status')) {
        Write-Host ''
        Write-Ok "操作完成：$Action"
    }
} catch {
    Write-Host ''
    Write-Fail $_.Exception.Message
    exit 1
}
