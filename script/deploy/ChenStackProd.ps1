[CmdletBinding()]
param(
    [ValidateSet('check', 'status', 'up', 'stop', 'restart', 'replace-backend', 'replace-user')]
    [string]$Action = 'status',
    [ValidatePattern('^sha256:[a-f0-9]{64}$')]
    [string]$BackendImage,
    [ValidatePattern('^sha256:[a-f0-9]{64}$')]
    [string]$ExpectedBackendImage,
    [ValidatePattern('^sha256:[a-f0-9]{64}$')]
    [string]$UserImage,
    [ValidatePattern('^sha256:[a-f0-9]{64}$')]
    [string]$ExpectedUserImage,
    [ValidatePattern('^sha256:[a-f0-9]{64}$')]
    [string]$UserRollbackImage
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$runtime = Join-Path $repo '.runtime/prod'
$composeFile = Join-Path $runtime 'compose.json'
$backup = 'D:\ChenStack-Backups'
$network = 'chen-stack-network'
$data = [ordered]@{
    mysql = 'chen-stack-mysql'
    redis = 'chen-stack-redis'
    minio = 'chen-stack-minio'
    rabbitmq = 'chen-stack-rabbitmq'
    qdrant = 'chen-qdrant-release'
}
$apps = [ordered]@{
    backend = 'chen-stack-backend'
    admin = 'chen-stack-admin'
    user = 'chen-stack-user'
}
$names = $data + $apps
$volumeTargets = @{
    mysql = '/var/lib/mysql'
    redis = '/data'
    minio = '/data'
    rabbitmq = '/var/lib/rabbitmq'
    qdrant = '/qdrant/storage'
}
$binds = @{
    mysql = @{ '/docker-entrypoint-initdb.d/init.sql' = (Join-Path $repo 'sql/chen_stack.sql') }
    redis = @{
        '/secure' = (Join-Path $runtime 'redis/config')
        '/run/secrets/redis-health-password' = (Join-Path $runtime 'redis/redis-app.secret')
    }
    qdrant = @{ '/qdrant/tls' = (Join-Path $runtime 'rag/tls') }
    backend = @{
        '/release' = (Join-Path $runtime 'rag/control')
        '/opt/java/openjdk/lib/security/cacerts' = (Join-Path $runtime 'rag/tls/qdrant-truststore.p12')
        '/ai-usage' = (Join-Path $runtime 'ai-usage')
    }
}
$composeProject = @('compose', '-p', 'chen-stack-prod', '--project-directory', $runtime)
$compose = $composeProject + @('-f', $composeFile)

function Assert-LocalPath([string]$Path) {
    $item = Get-Item -LiteralPath $Path -Force
    while ($item) {
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw "部署路径包含链接或重解析点：$Path"
        }
        $item = if ($item -is [IO.DirectoryInfo]) { $item.Parent } else { $item.Directory }
    }
}

function Invoke-CheckedDocker([string[]]$Arguments, [string]$Description) {
    $result = & docker @Arguments 2>$null
    if ($LASTEXITCODE -ne 0) { throw "$Description 失败（Docker exit=$LASTEXITCODE）" }
    return $result
}

function Wait-BackendReadiness {
    $deadline = (Get-Date).AddSeconds(120)
    do {
        try {
            $ready = Invoke-RestMethod 'http://127.0.0.1:5000/actuator/health/readiness' -TimeoutSec 3
            if ($ready.status -ceq 'UP') { return }
        } catch { }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw '正式后端未在 120 秒内就绪'
}

function Confirm-AppReadiness([string]$Service, [string]$Image) {
    if ($Service -eq 'backend') {
        Wait-BackendReadiness
    } else {
        $deadline = (Get-Date).AddSeconds(30)
        $response = $null
        do {
            try {
                $response = Invoke-WebRequest 'http://127.0.0.1:7000/' -TimeoutSec 3
                if ($response.StatusCode -eq 200) { break }
            } catch { }
            Start-Sleep -Seconds 1
        } while ((Get-Date) -lt $deadline)
        if ($null -eq $response -or $response.StatusCode -ne 200) { throw '用户端未通过 HTTP 检查' }
    }
    $actual = Invoke-CheckedDocker @('inspect', '--format', '{{.Image}}', $apps[$Service]) "核对运行镜像 $Service"
    if ($actual -cne $Image) { throw "运行镜像与请求镜像不一致：$Service" }
}

function Set-AppCompose([byte[]]$Content, [string]$ExpectedHash, [switch]$Validate) {
    $stage = Join-Path $runtime "compose-app-$([guid]::NewGuid().ToString('N')).json"
    try {
        [IO.File]::WriteAllBytes($stage, $Content)
        if ($Validate) {
            $null = Invoke-CheckedDocker ($composeProject + @('-f', $stage, 'config', '--quiet')) '候选 Compose 预检'
        }
        if ((Get-FileHash -LiteralPath $composeFile -Algorithm SHA256).Hash -cne $ExpectedHash) {
            throw '正式 Compose 自校验后已变化，停止镜像切换'
        }
        [IO.File]::Replace($stage, $composeFile, [NullString]::Value)
    } finally {
        if (Test-Path -LiteralPath $stage -PathType Leaf) { Remove-Item -LiteralPath $stage }
    }
}

$deployLock = $null
$deployLockHeld = $false
try {
    if (($Action -ne 'replace-backend' -and ($BackendImage -or $ExpectedBackendImage)) -or
        ($Action -ne 'replace-user' -and ($UserImage -or $ExpectedUserImage -or $UserRollbackImage))) {
        throw '镜像参数仅用于对应的替换操作'
    }
    $replaceService = switch ($Action) {
        'replace-backend' { 'backend' }
        'replace-user' { 'user' }
    }
    if ($replaceService) {
        $nextImage = if ($replaceService -eq 'backend') { $BackendImage } else { $UserImage }
        $expectedImage = if ($replaceService -eq 'backend') { $ExpectedBackendImage } else { $ExpectedUserImage }
        if (!$nextImage -or !$expectedImage -or $nextImage -ceq $expectedImage) {
            throw "请提供 $replaceService 不同的候选和当前镜像 SHA256"
        }
        $rollbackImage = if ($UserRollbackImage) { $UserRollbackImage } else { $expectedImage }
        if ($rollbackImage -ceq $nextImage) { throw '回滚镜像必须与候选镜像不同' }
        $deployLock = [Threading.Mutex]::new($false, 'ChenStackProdBackendReplace')
        $deployLockHeld = $deployLock.WaitOne(0)
        if (!$deployLockHeld) { throw '已有正式应用镜像替换正在执行' }
    }
    Assert-LocalPath $composeFile
    Assert-LocalPath $backup
    if (-not (Test-Path -LiteralPath $backup -PathType Container)) { throw '正式备份根目录不符' }
    try {
        $original = [IO.File]::ReadAllBytes($composeFile)
        $originalHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($original))
        $definition = [Text.Encoding]::UTF8.GetString($original).TrimStart([char]0xFEFF) | ConvertFrom-Json -AsHashtable
    } catch {
        throw '正式私有 Compose JSON 无法解析'
    }
    foreach ($key in $definition.Keys) {
        if ($key -cnotin @('services', 'networks', 'volumes')) { throw '正式 Compose 配置包含额外输入' }
    }
    foreach ($service in $definition.services.Values) {
        foreach ($key in $service.Keys) {
            if ($key -cnotin @('image', 'container_name', 'pull_policy', 'restart', 'environment', 'networks',
                'entrypoint', 'command', 'user', 'working_dir', 'hostname', 'ports', 'healthcheck', 'volumes')) {
                throw '正式 Compose 服务包含未核验的配置项'
            }
        }
    }
    try {
        $model = Invoke-CheckedDocker ($compose + @('config', '--format', 'json')) '正式 Compose 配置校验' |
            ConvertFrom-Json -AsHashtable
    } catch {
        throw '正式 Compose 配置无法解析或包含无效选项'
    }
    if ($model.services.Count -ne $names.Count -or $model.networks.Count -ne 1 -or
        $model.volumes.Count -ne $data.Count -or $model.networks.prod.name -ne $network -or
        -not $model.networks.prod.external) { throw '正式服务、网络或数据卷定义不符' }
    $null = Invoke-CheckedDocker @('network', 'inspect', $network) '正式网络检查'
    $environment = $model.services.backend.environment
    $libraryEnabled = $environment['CHEN_KNOWLEDGE_SIMPLE_ENABLED'] -ceq 'true'
    $backendWritable = @('/ai-usage')
    if ($libraryEnabled) {
        $binds.backend.Remove('/release')
        $binds.backend['/data/knowledge/uploads'] = Join-Path $runtime 'knowledge/uploads'
        $binds.backend['/data/ai'] = Join-Path $runtime 'ai'
        $backendWritable += @('/data/knowledge/uploads', '/data/ai')
        if ($model.services.backend.volumes.target -ccontains '/data/knowledge/curated') {
            $binds.backend['/data/knowledge/curated'] = Join-Path $runtime 'knowledge/curated'
            $backendWritable += '/data/knowledge/curated'
        }
    }

    foreach ($key in $names.Keys) {
        $service = $model.services[$key]
        if ($service.container_name -cne $names[$key] -or $service.image -cnotmatch '^sha256:[a-f0-9]{64}$' -or
            $service.pull_policy -ne 'never' -or $service.restart -ne 'unless-stopped' -or
            $service.networks.Count -ne 1 -or -not $service.networks.Contains('prod')) {
            throw "正式服务配置不符：$key"
        }
        $imageToCheck = if ($key -eq 'user' -and $UserRollbackImage) { $UserRollbackImage } else { $service.image }
        $null = Invoke-CheckedDocker @('image', 'inspect', $imageToCheck) "正式镜像 $key 检查"
        $expectedBinds = $binds[$key]
        $expectedMountCount = if ($expectedBinds) { $expectedBinds.Count } else { 0 }
        if ($volumeTargets.ContainsKey($key)) { $expectedMountCount++ }
        $mounts = @($service['volumes'] | Where-Object { $_ })
        if ($mounts.Count -ne $expectedMountCount -or @($mounts | ForEach-Object { $_.target } | Sort-Object -Unique).Count -ne $mounts.Count) {
            throw "正式挂载数量或目标不符：$key"
        }
        foreach ($mount in $mounts) {
            if ($mount.type -eq 'bind' -and $expectedBinds -and $expectedBinds.ContainsKey($mount.target)) {
                $expected = [IO.Path]::GetFullPath($expectedBinds[$mount.target])
                if ([IO.Path]::GetFullPath($mount.source, $runtime) -ne $expected -or $mount['bind']['create_host_path'] -or
                    ($key -ne 'mysql' -and [bool]$mount['read_only'] -ne ($mount.target -cnotin $backendWritable))) {
                    throw "正式 Bind Mount 路径或权限不符：$key $($mount.target)"
                }
                Assert-LocalPath $expected
                if ((Test-Path -LiteralPath $expected -PathType Container) -and
                    (Get-ChildItem -LiteralPath $expected -Force -Recurse -Attributes ReparsePoint | Select-Object -First 1)) {
                    throw "正式 Bind Mount 包含链接或重解析点：$key $($mount.target)"
                }
            } elseif ($mount.type -eq 'volume' -and $volumeTargets[$key] -ceq $mount.target -and
                $mount.source -ceq $key -and -not $mount['read_only'] -and $model.volumes[$key].external -and
                $model.volumes[$key].name -ceq "$($names[$key])-data") {
                $null = Invoke-CheckedDocker @('volume', 'inspect', $model.volumes[$key].name) "正式数据卷 $key 检查"
            } else {
                throw "正式挂载定义不符：$key $($mount.target)"
            }
        }
    }

    $paths = @{
        AI_USAGE_LEDGER_PATH = '/ai-usage/events.jsonl'
    }
    if ($libraryEnabled) {
        $paths['CHEN_KNOWLEDGE_SIMPLE_UPLOADS'] = '/data/knowledge/uploads'
        $paths['CHEN_KNOWLEDGE_SIMPLE_SETTINGS'] = '/data/ai/settings.yml'
        foreach ($key in 'CHEN_RAG_RELEASE_ENABLED','CHEN_RAG_RELEASE_RECOVERY_ENABLED','CHEN_KNOWLEDGE_ENABLED','CHEN_WIKI_ENABLED','CHEN_RAG_ENABLED') {
            if ($environment[$key] -cne 'false') { throw "正式知识库启用时旧通道须停用：$key" }
        }
        if ($environment['CHEN_AI_CHAT_HISTORY_ENABLED'] -cne 'true') { throw '正式客服会话持久化未开启' }
    } else {
        $paths['CHEN_RAG_RELEASE_MANIFEST_PATH'] = '/release/manifest.json'
        $paths['CHEN_RAG_RELEASE_OPERATION_PATH'] = '/release/operation-cfgfix.json'
        $paths['CHEN_RAG_RELEASE_ARTIFACT_PATH'] = '/app/app.jar'
    }
    foreach ($key in $paths.Keys) {
        if ($environment[$key] -cne $paths[$key]) { throw "正式容器内路径不符：$key" }
    }
    Assert-LocalPath (Join-Path $runtime 'ai-usage/events.jsonl')
    if (-not $libraryEnabled) {
        foreach ($path in 'rag/control/manifest.json', 'rag/control/operation-cfgfix.json') {
            Assert-LocalPath (Join-Path $runtime $path)
        }
        $manifest = Get-Content -Raw -LiteralPath (Join-Path $runtime 'rag/control/manifest.json') | ConvertFrom-Json
        $operation = Get-Content -Raw -LiteralPath (Join-Path $runtime 'rag/control/operation-cfgfix.json') | ConvertFrom-Json
        if ($operation.manifestSha256 -ne (Get-FileHash -LiteralPath (Join-Path $runtime 'rag/control/manifest.json')).Hash -or
            $operation.allowedOperation -cne 'RECOVER' -or $operation.environment -cne $manifest.environment -or
            $manifest.operation -cne 'RECOVER' -or $manifest.environment -cne 'production') {
            throw '正式发布清单与 RECOVER 记录不匹配'
        }
    }
    if ($Action -eq 'check') { Write-Host '正式部署配置检查通过'; exit 0 }

    $containers = @{}
    $inventory = Invoke-CheckedDocker @('ps', '-a', '--format', '{{.Names}}') '读取容器清单'
    foreach ($key in $names.Keys) {
        if ($names[$key] -cnotin $inventory) { continue }
        $container = (Invoke-CheckedDocker @('container', 'inspect', $names[$key]) "检查 $key 容器" |
            ConvertFrom-Json -AsHashtable -NoEnumerate)[0]
        if ($container.Image -cne $model.services[$key].image -or
            -not $container.NetworkSettings.Networks.Contains($network)) {
            throw "同名容器的镜像或网络与正式部署不符：$key"
        }
        if ($volumeTargets.ContainsKey($key) -and -not ($container.Mounts | Where-Object {
            $_.Type -ceq 'volume' -and $_.Name -ceq "$($names[$key])-data" -and $_.Destination -ceq $volumeTargets[$key]
        })) { throw "同名容器未使用指定的正式数据卷：$key" }
        $containers[$key] = $container
    }

    if ($replaceService) {
        $running = $containers[$replaceService]
        if (!$running -or !$running.State.Running -or
            $running.Config.Labels['com.docker.compose.project'] -cne 'chen-stack-prod' -or
            $running.Config.Labels['com.docker.compose.service'] -cne $replaceService -or
            $running.Image -cne $expectedImage) {
            throw "运行容器身份或预期镜像不符，禁止替换：$replaceService"
        }
        if ($replaceService -eq 'backend' -and (!$libraryEnabled -or
            ($running.Mounts | Where-Object Destination -CEQ '/release') -or
            ($running.Config.Env -ccontains 'CHEN_RAG_RELEASE_RECOVERY_ENABLED=true') -or
            ($running.Config.Labels.Keys -like 'chen.release*'))) {
            throw '运行后端发布模式不符，禁止替换'
        }
        $candidateId = Invoke-CheckedDocker @('image', 'inspect', '--format', '{{.Id}}', $nextImage) '核对本机构建候选镜像'
        if ($candidateId -cne $nextImage) { throw '候选镜像身份不一致' }
        Confirm-AppReadiness $replaceService $expectedImage
        $rollbackConfig = $original
        if ($UserRollbackImage -and $UserRollbackImage -cne $expectedImage) {
            $definition.services.user.image = $UserRollbackImage
            $rollbackConfig = [Text.Encoding]::UTF8.GetBytes(($definition | ConvertTo-Json -Depth 50))
        }
        $definition.services[$replaceService].image = $nextImage
        $candidate = [Text.Encoding]::UTF8.GetBytes(($definition | ConvertTo-Json -Depth 50))
        $candidateHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($candidate))
        $replace = $compose + @('up', '-d', '--no-deps', '--no-build', '--pull', 'never', $replaceService)
        Set-AppCompose $candidate $originalHash -Validate
        try {
            $null = Invoke-CheckedDocker $replace "替换正式服务 $replaceService"
            Confirm-AppReadiness $replaceService $nextImage
            Write-Host "正式服务 $replaceService 已就绪：$nextImage；回滚镜像：$rollbackImage"
        } catch {
            $failure = $_.Exception.Message
            Set-AppCompose $rollbackConfig $candidateHash
            $null = Invoke-CheckedDocker $replace "恢复原服务 $replaceService"
            Confirm-AppReadiness $replaceService $rollbackImage
            throw "$failure；已恢复 $replaceService 回滚镜像 $rollbackImage"
        }
    } elseif ($Action -eq 'status') {
        foreach ($key in $names.Keys) {
            $state = if ($containers.ContainsKey($key)) { $containers[$key].State.Status } else { 'missing' }
            Write-Host "$($names[$key]) `t$state"
        }
    } elseif ($Action -eq 'up') {
        foreach ($key in $names.Keys) {
            if (-not $containers.ContainsKey($key)) {
                $null = Invoke-CheckedDocker ($compose + @('up', '-d', '--no-deps', '--no-build', '--pull', 'never', $key)) "创建 $key"
            } elseif (-not $containers[$key].State.Running) {
                $null = Invoke-CheckedDocker @('start', $containers[$key].Id) "启动 $key"
            }
        }
        Wait-BackendReadiness
    } else {
        foreach ($key in $apps.Keys) {
            if ($containers.ContainsKey($key) -and $containers[$key].State.Running) {
                $null = Invoke-CheckedDocker @($Action, $containers[$key].Id) "$Action $key"
            }
        }
        if ($Action -eq 'restart' -and $containers.ContainsKey('backend') -and $containers.backend.State.Running) {
            Wait-BackendReadiness
        }
    }
} catch {
    Write-Error $_.Exception.Message -ErrorAction Continue
    exit 1
} finally {
    if ($deployLockHeld) { $deployLock.ReleaseMutex() }
    if ($null -ne $deployLock) { $deployLock.Dispose() }
}
