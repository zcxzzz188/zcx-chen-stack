#requires -Version 7.0
<#
.SYNOPSIS
    辰栈 Docker 部署诊断（doctor）与端口发布安全恢复（repair-ports）引擎。

.DESCRIPTION
    本文件只定义函数，由 script/deploy/ChenStack.ps1 点源加载，不单独执行。
    统一入口始终是：

        pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action doctor
        pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action repair-ports

    设计约束：
      * 诊断只读：不启动、停止、重启、删除容器，不改镜像标签、卷或网络。
      * 不使用整份 docker inspect JSON 做 ConvertFrom-Json；只读取 Go Template
        提取的单个字段（{{json .Field}}）或纯文本字段。
      * 输出摘要不含口令、Token、API Key、邮件凭据。日志行统一脱敏。
      * 恢复动作仅在“容器内健康 UP 但宿主机实际端口绑定缺失”时可用，
        有尝试次数上限，不删除任何数据或镜像。

.NOTES
    内部使用的数据形状为普通 hashtable / pscustomobject，便于测试注入。
#>

$script:DoctorBackendContainerPort = 5000
$script:DoctorInfraServices = @('mysql', 'redis', 'minio', 'rabbitmq')
$script:DoctorAppServices = @('backend', 'frontend-admin', 'frontend-user')
$script:DoctorDockerOwnerPattern = '(?i)^(docker|com\.docker|dockerd|wslrelay|vpnkit|com\.docker\.backend|docker-proxy)'

# ---------------------------------------------------------------- 默认探针

function Invoke-DoctorDockerProcess {
    <# 默认 docker 探针：永不抛异常，返回 ExitCode/Output。 #>
    param([string[]]$Arguments = @())

    try {
        $raw = & docker @Arguments 2>&1
        $code = $LASTEXITCODE
        $lines = @($raw | ForEach-Object { [string]$_ })
        return @{ ExitCode = $code; Output = ($lines -join "`n").Trim() }
    } catch {
        return @{ ExitCode = 127; Output = $_.Exception.Message }
    }
}

function Invoke-DoctorHttpGetProcess {
    <# 默认 HTTP 探针：只用 IPv4 loopback，不跟随外部代理。 #>
    param(
        [Parameter(Mandatory)][string]$Uri,
        [int]$TimeoutSeconds = 5
    )

    try {
        $response = Invoke-WebRequest -Uri $Uri -TimeoutSec $TimeoutSeconds -NoProxy -UseBasicParsing -ErrorAction Stop
        # actuator 返回的 Content-Type 不是 web 常用类型，PowerShell 会把 Content 保留为 byte[]。
        $body = ''
        $content = $response.Content
        if ($content -is [byte[]]) {
            $body = [System.Text.Encoding]::UTF8.GetString($content)
        } elseif ($content -is [System.IO.Stream]) {
            $reader = [System.IO.StreamReader]::new($content, [System.Text.Encoding]::UTF8)
            try { $body = $reader.ReadToEnd() } finally { $reader.Dispose() }
        } elseif ($null -ne $content) {
            $body = [string]$content
        }
        $status = [int]$response.StatusCode
        return @{
            Ok         = ($status -ge 200 -and $status -lt 400)
            StatusCode = $status
            Body       = $body
            Error      = ''
        }
    } catch {
        $status = 0
        try {
            if ($null -ne $_.Exception.Response -and $null -ne $_.Exception.Response.StatusCode) {
                $status = [int]$_.Exception.Response.StatusCode
            }
        } catch { }
        return @{ Ok = $false; StatusCode = $status; Body = ''; Error = $_.Exception.Message }
    }
}

function Get-DoctorPortOwnerProcess {
    <# 查询 Windows TCP 监听与占用进程；不支持时返回 Supported=$false。 #>
    param([Parameter(Mandatory)][int]$Port)

    $supported = $null -ne (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue)
    if (-not $supported) {
        return @{ Supported = $false; Listening = $false; Pid = 0; ProcessName = ''; LocalAddress = '' }
    }
    try {
        $connection = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction Stop | Select-Object -First 1
        if ($null -eq $connection) {
            return @{ Supported = $true; Listening = $false; Pid = 0; ProcessName = ''; LocalAddress = '' }
        }
        $process = Get-Process -Id $connection.OwningProcess -ErrorAction SilentlyContinue
        $processName = if ($null -ne $process) { $process.ProcessName } else { 'unknown' }
        return @{
            Supported   = $true
            Listening   = $true
            Pid         = [int]$connection.OwningProcess
            ProcessName = $processName
            LocalAddress = [string]$connection.LocalAddress
        }
    } catch {
        return @{ Supported = $true; Listening = $false; Pid = 0; ProcessName = ''; LocalAddress = '' }
    }
}

function Get-DoctorExcludedPortRangeProcess {
    <# 读取 Windows TCP 排除端口范围。失败时返回空数组（不视为错误）。 #>
    $ranges = @()
    if (-not (Test-Path variable:global:IsWindows) -or -not $IsWindows) { return $ranges }
    try {
        $text = (netsh int ipv4 show excludedportrange protocol=tcp) 2>&1 | Out-String
    } catch {
        return $ranges
    }
    foreach ($line in ($text -split "`n")) {
        if ($line -match '^\s*(\d{1,5})\s+(\d{1,5})\s*(\*?)\s*$') {
            $ranges += [pscustomobject]@{
                Start        = [int]$Matches[1]
                End          = [int]$Matches[2]
                Administered = ($Matches[3] -eq '*')
            }
        }
    }
    return $ranges
}

# ---------------------------------------------------------------- 上下文

function New-ChenStackDoctorContext {
    <#
    .SYNOPSIS
        构造诊断上下文。所有外部依赖都可注入，便于隔离测试。
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$RepoRoot,
        [string]$Instance = 'dev',
        [System.Collections.IDictionary]$EnvMap = @{},
        [string]$EnvPath = '',
        [scriptblock]$Docker,
        [scriptblock]$HttpGet,
        [scriptblock]$PortOwner,
        [scriptblock]$ExcludedRanges,
        [int]$HttpTimeoutSeconds = 5
    )

    if (-not $EnvMap) { $EnvMap = @{} }
    $backendPort = Get-DoctorEnvPort -EnvMap $EnvMap -Key 'BACKEND_PORT' -Default 5000
    $adminPort = Get-DoctorEnvPort -EnvMap $EnvMap -Key 'ADMIN_PORT' -Default 8000
    $userPort = Get-DoctorEnvPort -EnvMap $EnvMap -Key 'USER_PORT' -Default 7000

    return [pscustomobject]@{
        RepoRoot             = $RepoRoot
        Instance             = $Instance
        EnvPath              = $EnvPath
        EnvMap               = $EnvMap
        BackendPort          = $backendPort
        AdminPort            = $adminPort
        UserPort             = $userPort
        BackendContainerPort = $script:DoctorBackendContainerPort
        HttpTimeoutSeconds   = $HttpTimeoutSeconds
        Docker               = if ($Docker) { $Docker } else { ${function:Invoke-DoctorDockerProcess} }
        HttpGet              = if ($HttpGet) { $HttpGet } else { ${function:Invoke-DoctorHttpGetProcess} }
        PortOwner            = if ($PortOwner) { $PortOwner } else { ${function:Get-DoctorPortOwnerProcess} }
        ExcludedRanges       = if ($ExcludedRanges) { $ExcludedRanges } else { ${function:Get-DoctorExcludedPortRangeProcess} }
    }
}

function Get-DoctorEnvPort {
    param($EnvMap, [string]$Key, [int]$Default)

    if ($null -eq $EnvMap) { return $Default }
    if (-not $EnvMap.Contains($Key)) { return $Default }
    $raw = [string]$EnvMap[$Key]
    $value = 0
    if ([int]::TryParse($raw.Trim(), [ref]$value) -and $value -ge 1 -and $value -le 65535) { return $value }
    return $Default
}

# ---------------------------------------------------------------- 基础工具

function Invoke-DoctorDocker {
    <#
    .SYNOPSIS
        调用 docker 探针。默认只读模式会拒绝任何写入类命令。
    #>
    param(
        [Parameter(Mandatory)]$Context,
        [Parameter(Mandatory)][string[]]$Arguments,
        [switch]$AllowWrite
    )

    if (-not $AllowWrite -and $Arguments.Count -gt 0) {
        $verb = [string]$Arguments[0]
        if ($verb -in @('restart', 'start', 'stop', 'kill', 'rm', 'rmi', 'create', 'run', 'update', 'tag',
                'pull', 'push', 'compose', 'volume', 'image', 'network', 'system', 'container', 'prune')) {
            return @{ ExitCode = 90; Output = "诊断只读模式拒绝写入命令：docker $($Arguments -join ' ')" }
        }
    }
    try {
        $result = & $Context.Docker $Arguments
    } catch {
        return @{ ExitCode = 127; Output = $_.Exception.Message }
    }
    if ($null -eq $result) { return @{ ExitCode = 127; Output = 'docker 探针无返回' } }
    $code = 127
    try { $code = [int]$result.ExitCode } catch { }
    $output = ''
    try { $output = [string]$result.Output } catch { }
    return @{ ExitCode = $code; Output = $output }
}

function ConvertFrom-DoctorJson {
    <# 解析单个字段的 JSON 文本；空值或非法内容返回 $null。 #>
    param([string]$Text)

    if ([string]::IsNullOrWhiteSpace($Text)) { return $null }
    try {
        return ($Text | ConvertFrom-Json -AsHashtable -ErrorAction Stop)
    } catch {
        return $null
    }
}

function ConvertFrom-DoctorJsonLines {
    <# 把多容器批量 inspect 的多行 JSON 解析为对象数组。 #>
    param([string]$Output)

    $items = @()
    foreach ($line in ($Output -split "`n")) {
        $trimmed = $line.Trim()
        if (-not $trimmed) { continue }
        $parsed = ConvertFrom-DoctorJson -Text $trimmed
        if ($null -ne $parsed) { $items += $parsed }
    }
    return $items
}

function ConvertFrom-DoctorPortMap {
    <# 把 PortBindings / NetworkSettings.Ports 的 JSON 结构展开为绑定列表。 #>
    param($Map)

    $entries = @()
    if ($null -eq $Map -or $Map -isnot [System.Collections.IDictionary]) { return , $entries }
    foreach ($key in $Map.Keys) {
        $value = $Map[$key]
        foreach ($binding in @($value)) {
            if ($null -eq $binding) { continue }
            if ($binding -isnot [System.Collections.IDictionary]) { continue }
            $hostIp = if ($binding.Contains('HostIp')) { [string]$binding['HostIp'] } else { '' }
            $hostPort = if ($binding.Contains('HostPort')) { [string]$binding['HostPort'] } else { '' }
            $entries += [pscustomobject]@{ Port = [string]$key; HostIp = $hostIp; HostPort = $hostPort }
        }
    }
    return , $entries
}

function Protect-DoctorText {
    <# 脱敏：日志、异常文本、docker 输出在展示前必须经过本函数。 #>
    param([string]$Text)

    if ([string]::IsNullOrWhiteSpace($Text)) { return '' }
    $sanitized = $Text
    $sanitized = [regex]::Replace($sanitized, '(?i)(password|passwd|pwd|secret|token|api[_-]?key|authorization|jwt)(\s*[=:]\s*)([^\s,;"'']+)', '$1$2***')
    $sanitized = [regex]::Replace($sanitized, '\beyJ[A-Za-z0-9_\-\.]{20,}', 'JWT***')
    $sanitized = [regex]::Replace($sanitized, '(?i)(-----BEGIN[^-]{0,40}-----)[\s\S]*?(-----END[^-]{0,40}-----)', '$1***$2')
    return $sanitized.Trim()
}

function Add-DoctorCheck {
    param($Result, [string]$Label, [string]$Status, [string]$Detail = '')

    $Result.Checks.Add([pscustomobject]@{
            Label  = $Label
            Status = $Status
            Detail = $Detail
        })
}

# ---------------------------------------------------------------- 容器清单

function Get-DoctorContainerInventory {
    <#
    .SYNOPSIS
        列出实例 Compose 项目下的全部容器（含已停止历史容器），并按服务选中当前容器。
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory)]$Context)

    $empty = [pscustomobject]@{ Ok = $true; Items = @(); Error = '' }
    $listing = Invoke-DoctorDocker -Context $Context -Arguments @(
        'ps', '-a', '--filter', "label=com.docker.compose.project=$($Context.Instance)", '--format', '{{.Names}}'
    )
    if ($listing.ExitCode -ne 0) {
        return [pscustomobject]@{
            Ok    = $false
            Items = @()
            Error = Protect-DoctorText (@($listing.Output -split "`n")[0])
        }
    }

    $names = @($listing.Output -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    if ($names.Count -eq 0) { return $empty }

    $labelCall = Invoke-DoctorDocker -Context $Context -Arguments (@('inspect') + $names + @('--format', '{{json .Config.Labels}}'))
    if ($labelCall.ExitCode -ne 0) {
        return [pscustomobject]@{ Ok = $false; Items = @(); Error = '无法读取实例容器标签' }
    }
    $stateCall = Invoke-DoctorDocker -Context $Context -Arguments (@('inspect') + $names + @('--format', '{{json .State}}'))
    if ($stateCall.ExitCode -ne 0) {
        return [pscustomobject]@{ Ok = $false; Items = @(); Error = '无法读取实例容器状态' }
    }

    $labelRows = @($labelCall.Output -split "`n" | Where-Object { $_.Trim() })
    $stateRows = @($stateCall.Output -split "`n" | Where-Object { $_.Trim() })
    if ($labelRows.Count -ne $names.Count -or $stateRows.Count -ne $names.Count) {
        return [pscustomobject]@{ Ok = $false; Items = @(); Error = '容器标签/状态返回行数与容器数不一致' }
    }

    $items = @()
    for ($index = 0; $index -lt $names.Count; $index++) {
        $labelMap = ConvertFrom-DoctorJson -Text $labelRows[$index]
        $stateMap = ConvertFrom-DoctorJson -Text $stateRows[$index]
        if ($labelMap -isnot [System.Collections.IDictionary]) { continue }
        if (-not $labelMap.Contains('com.docker.compose.service')) { continue }
        $service = [string]$labelMap['com.docker.compose.service']
        if (-not $service) { continue }
        $status = ''
        $running = $false
        if ($stateMap -is [System.Collections.IDictionary]) {
            if ($stateMap.Contains('Status')) { $status = [string]$stateMap['Status'] }
            if ($stateMap.Contains('Running')) { $running = [bool]$stateMap['Running'] }
        }
        $items += [pscustomobject]@{
            Name    = $names[$index]
            Service = $service
            State   = $status
            Running = $running
        }
    }
    return [pscustomobject]@{ Ok = $true; Items = $items; Error = '' }
}

function Select-DoctorServiceContainer {
    <#
    .SYNOPSIS
        从同项目容器中选出某服务的当前容器；优先运行中容器，历史容器只作为历史记录。
    #>
    param([object[]]$Items, [string]$Service)

    $candidates = @($Items | Where-Object { $_.Service -eq $Service })
    $running = @($candidates | Where-Object { $_.Running })
    $historical = @($candidates | Where-Object { -not $_.Running })
    $selected = $null
    $ambiguous = $false
    if ($running.Count -ge 1) {
        $selected = $running[0]
        if ($running.Count -gt 1) { $ambiguous = $true }
    } elseif ($candidates.Count -ge 1) {
        $selected = $candidates[0]
    }
    return [pscustomobject]@{
        Selected   = $selected
        Historical = $historical
        Candidates = $candidates
        Ambiguous  = $ambiguous
    }
}

# ---------------------------------------------------------------- 容器事实

function Get-DoctorContainerState {
    <# 批量读取容器身份与 health 状态。 #>
    param($Context, [string[]]$Names)

    $result = @{}
    if ($Names.Count -eq 0) { return $result }
    $identity = Invoke-DoctorDocker -Context $Context -Arguments (@('inspect') + $Names + @(
            '--format', '{{.Id}},{{.State.Status}},{{.State.Running}},{{.Image}},{{.Config.Image}},{{.HostConfig.RestartPolicy.Name}}'
        ))
    if ($identity.ExitCode -eq 0) {
        $rows = @($identity.Output -split "`n" | Where-Object { $_.Trim() })
        if ($rows.Count -eq $Names.Count) {
            for ($index = 0; $index -lt $Names.Count; $index++) {
                $parts = $rows[$index].Split(',')
                $result[$Names[$index]] = [pscustomobject]@{
                    Id            = if ($parts.Count -gt 0) { $parts[0].Trim() } else { '' }
                    Status        = if ($parts.Count -gt 1) { $parts[1].Trim() } else { '' }
                    Running       = if ($parts.Count -gt 2) { $parts[2].Trim() -eq 'true' } else { $false }
                    ImageId       = if ($parts.Count -gt 3) { $parts[3].Trim() } else { '' }
                    ImageName     = if ($parts.Count -gt 4) { $parts[4].Trim() } else { '' }
                    RestartPolicy = if ($parts.Count -gt 5) { $parts[5].Trim() } else { '' }
                }
            }
        }
    }
    return $result
}

function Get-DoctorContainerHealth {
    param($Context, [string[]]$Names)

    $result = @{}
    if ($Names.Count -eq 0) { return $result }
    $call = Invoke-DoctorDocker -Context $Context -Arguments (@('inspect') + $Names + @(
            '--format', '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}'
        ))
    if ($call.ExitCode -ne 0) { return $result }
    $rows = @($call.Output -split "`n" | Where-Object { $_.Trim() })
    if ($rows.Count -ne $Names.Count) { return $result }
    for ($index = 0; $index -lt $Names.Count; $index++) {
        $result[$Names[$index]] = $rows[$index].Trim()
    }
    return $result
}

function Get-DoctorBackendFacts {
    <#
    .SYNOPSIS
        读取后端容器的端口发布、挂载、标签、环境变量和网络。只解析单字段 JSON。
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory)]$Context, [Parameter(Mandatory)][string]$Name)

    $facts = [pscustomobject]@{
        Name              = $Name
        Error             = ''
        HostBindings      = @()
        PublishedBindings = @()
        PortsKeyPresent   = $false
        Mounts            = @()
        Labels            = @{}
        Env               = @()
        Networks          = @()
    }

    $hostCall = Invoke-DoctorDocker -Context $Context -Arguments @('inspect', $Name, '--format', '{{json .HostConfig.PortBindings}}')
    if ($hostCall.ExitCode -ne 0) { $facts.Error = '无法读取 HostConfig.PortBindings'; return $facts }
    $facts.HostBindings = ConvertFrom-DoctorPortMap -Map (ConvertFrom-DoctorJson -Text $hostCall.Output)

    $publishedCall = Invoke-DoctorDocker -Context $Context -Arguments @('inspect', $Name, '--format', '{{json .NetworkSettings.Ports}}')
    if ($publishedCall.ExitCode -ne 0) { $facts.Error = '无法读取 NetworkSettings.Ports'; return $facts }
    $publishedMap = ConvertFrom-DoctorJson -Text $publishedCall.Output
    if ($publishedMap -is [System.Collections.IDictionary]) {
        $facts.PortsKeyPresent = $publishedMap.Contains("$($Context.BackendContainerPort)/tcp")
    }
    $facts.PublishedBindings = ConvertFrom-DoctorPortMap -Map $publishedMap

    $mountCall = Invoke-DoctorDocker -Context $Context -Arguments @('inspect', $Name, '--format', '{{json .Mounts}}')
    if ($mountCall.ExitCode -eq 0) {
        foreach ($mount in @(ConvertFrom-DoctorJson -Text $mountCall.Output)) {
            if ($mount -isnot [System.Collections.IDictionary]) { continue }
            $facts.Mounts += [pscustomobject]@{
                Destination = if ($mount.Contains('Destination')) { [string]$mount['Destination'] } else { '' }
                Source      = if ($mount.Contains('Source')) { [string]$mount['Source'] } else { '' }
                RW          = if ($mount.Contains('RW')) { [bool]$mount['RW'] } else { $false }
                Type        = if ($mount.Contains('Type')) { [string]$mount['Type'] } else { '' }
            }
        }
    }

    $labelCall = Invoke-DoctorDocker -Context $Context -Arguments @('inspect', $Name, '--format', '{{json .Config.Labels}}')
    if ($labelCall.ExitCode -eq 0) {
        $labels = ConvertFrom-DoctorJson -Text $labelCall.Output
        if ($labels -is [System.Collections.IDictionary]) { $facts.Labels = $labels }
    }

    $envCall = Invoke-DoctorDocker -Context $Context -Arguments @('inspect', $Name, '--format', '{{json .Config.Env}}')
    if ($envCall.ExitCode -eq 0) {
        $facts.Env = @(ConvertFrom-DoctorJson -Text $envCall.Output)
    }

    $networkCall = Invoke-DoctorDocker -Context $Context -Arguments @('inspect', $Name, '--format', '{{json .NetworkSettings.Networks}}')
    if ($networkCall.ExitCode -eq 0) {
        $networks = ConvertFrom-DoctorJson -Text $networkCall.Output
        if ($networks -is [System.Collections.IDictionary]) { $facts.Networks = @($networks.Keys) }
    }

    return $facts
}

function Get-DoctorReleaseEvidence {
    <# 识别受控发布实例：标签、/release 挂载或 RAG 恢复开关。只返回证据名称，不返回值。 #>
    param($Facts)

    $evidence = @()
    if ($Facts.Labels -is [System.Collections.IDictionary]) {
        foreach ($key in $Facts.Labels.Keys) {
            if ($key -like 'chen.release*') { $evidence += "标签 $key"; break }
        }
    }
    foreach ($mount in @($Facts.Mounts)) {
        if ($mount.Destination -eq '/release') { $evidence += '挂载 /release' }
    }
    foreach ($entry in @($Facts.Env)) {
        if ([string]$entry -eq 'CHEN_RAG_RELEASE_RECOVERY_ENABLED=true') { $evidence += '环境 CHEN_RAG_RELEASE_RECOVERY_ENABLED' }
    }
    return $evidence
}

function Test-DoctorBackendHealth {
    <# 在容器内请求 actuator/health；返回 UP / DOWN / UNKNOWN。 #>
    param($Context, [string]$Name)

    if (-not $Name) { return @{ Status = 'UNKNOWN'; Detail = '后端容器不存在' } }
    $call = Invoke-DoctorDocker -Context $Context -Arguments @(
        'exec', $Name, 'wget', '-q', '-O-', "http://127.0.0.1:$($Context.BackendContainerPort)/actuator/health"
    )
    if ($call.ExitCode -ne 0) {
        $first = (@($call.Output -split "`n")[0])
        return @{ Status = 'DOWN'; Detail = Protect-DoctorText "容器内健康请求失败：$first" }
    }
    if ($call.Output -match '"status"\s*:\s*"UP"') { return @{ Status = 'UP'; Detail = '容器内 /actuator/health = UP' } }
    if ($call.Output -match '"status"\s*:\s*"OUT_OF_SERVICE"') { return @{ Status = 'DOWN'; Detail = '容器内 /actuator/health = OUT_OF_SERVICE' } }
    return @{ Status = 'DOWN'; Detail = '容器内 /actuator/health 未返回 UP' }
}

function Get-DoctorLogFindings {
    <# 抓取后端最近日志中的关键错误行，脱敏后返回。 #>
    param($Context, [string]$Name, [int]$Tail = 120)

    if (-not $Name) { return @() }
    $call = Invoke-DoctorDocker -Context $Context -Arguments @('logs', '--tail', [string]$Tail, $Name)
    if ($call.ExitCode -ne 0) { return @() }

    $patterns = @(
        'APPLICATION FAILED TO START',
        'Error starting ApplicationContext',
        'Failed to configure a DataSource',
        'Communications link failure',
        'Access denied for user',
        'Connection refused',
        'OutOfMemoryError',
        'PUBLICATION_[A-Z_]+',
        'Unable to start web server',
        'BeanCreationException'
    )
    $findings = @()
    foreach ($line in ($call.Output -split "`n")) {
        $trimmed = $line.Trim()
        if (-not $trimmed) { continue }
        foreach ($pattern in $patterns) {
            if ($trimmed -match $pattern) {
                $findings += Protect-DoctorText $trimmed
                break
            }
        }
        if ($findings.Count -ge 5) { break }
    }
    return $findings
}

function Get-DoctorRequestTarget {
    <#
    .SYNOPSIS
        读取前端镜像内嵌的后端 API 地址，判断是否与当前发布端口一致。
    #>
    param($Context, [string]$Name)

    $result = [pscustomobject]@{ Urls = @(); Consistent = $null; Detail = '' }
    if (-not $Name) { $result.Detail = '前端容器不存在'; return $result }

    $script = "grep -rhoE 'http://(localhost|127\.0\.0\.1):[0-9]+' /usr/share/nginx/html/assets 2>/dev/null | sort -u | head -n 10"
    $call = Invoke-DoctorDocker -Context $Context -Arguments @('exec', $Name, 'sh', '-c', $script)
    if ($call.ExitCode -ne 0) { $result.Detail = '无法读取前端内嵌 API 地址'; return $result }

    $urls = @($call.Output -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ -match '^http://' })
    $result.Urls = $urls
    if ($urls.Count -eq 0) { $result.Detail = '未在前端产物中找到内嵌 API 地址'; return $result }

    $consistent = $true
    foreach ($url in $urls) {
        if ($url -notmatch '^http://(?:localhost|127\.0\.0\.1):(\d+)$') { $consistent = $false; continue }
        if ([int]$Matches[1] -ne $Context.BackendPort) { $consistent = $false }
    }
    $result.Consistent = $consistent
    $result.Detail = ($urls -join ' ')
    return $result
}

function Test-DoctorDockerOwnedProcess {
    param([string]$ProcessName)

    if (-not $ProcessName) { return $false }
    return ($ProcessName -match $script:DoctorDockerOwnerPattern)
}

# ---------------------------------------------------------------- 诊断引擎

function Invoke-ChenStackDoctor {
    <#
    .SYNOPSIS
        执行完整只读诊断，返回结构化结果（含 Checks / Summary / RootCause）。
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory)]$Context)

    $result = [pscustomobject]@{
        Instance           = $Context.Instance
        BackendPort        = $Context.BackendPort
        GeneratedAt        = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
        Checks             = [System.Collections.Generic.List[object]]::new()
        EngineOk           = $false
        EngineVersion      = ''
        EngineError        = ''
        Containers         = @()
        Historical         = @()
        MissingServices    = @()
        StoppedServices    = @()
        AmbiguousServices  = @()
        InfraStates        = @{}
        InfraOk            = $true
        BackendName        = ''
        BackendRunning     = $false
        BackendFacts       = $null
        HostBindingOk      = $false
        HostBindingDetail  = ''
        BackendPublished   = $false
        PublishDetail      = ''
        InternalHealth     = 'UNKNOWN'
        InternalDetail     = ''
        HostAccess         = 'FAIL'
        HostDetail         = ''
        HostStatusCode     = 0
        PortOwner          = $null
        PortOwners         = [ordered]@{}
        ExcludedRanges     = @()
        FrontendUser       = 'UNKNOWN'
        FrontendUserDetail = ''
        FrontendAdmin      = 'UNKNOWN'
        FrontendAdminDetail = ''
        RequestTarget      = 'UNKNOWN'
        RequestTargetDetail = ''
        NetworkOk          = 'UNKNOWN'
        NetworkDetail      = ''
        ReleaseManaged     = $false
        ReleaseEvidence    = @()
        LogFindings        = @()
        PossibleCauses     = @()
        RootCause          = 'NONE'
        RecommendedAction  = 'pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action doctor'
        Ok                 = $false
        Summary            = @()
    }

    # 1. Docker Engine
    $engine = Invoke-DoctorDocker -Context $Context -Arguments @('version', '--format', '{{.Server.Version}}')
    if ($engine.ExitCode -ne 0) {
        $result.EngineError = Protect-DoctorText (@($engine.Output -split "`n")[0])
        $desktopHint = ''
        if ((Test-Path variable:global:IsWindows) -and $IsWindows) {
            $desktopProcess = Get-Process -Name 'Docker Desktop' -ErrorAction SilentlyContinue
            $desktopHint = if ($null -ne $desktopProcess) { '（Docker Desktop 进程存在，但 Engine 未响应）' } else { '（未检测到 Docker Desktop 进程）' }
        }
        Add-DoctorCheck $result 'Docker Engine' 'FAIL' "无法使用 Docker Engine：$($result.EngineError)$desktopHint"
        $result.RootCause = 'DOCKER_ENGINE_UNAVAILABLE'
        $result.RecommendedAction = '先启动 Docker Desktop 并等待 Engine 就绪；然后运行 pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action doctor'
        $result.Summary = Get-ChenStackDoctorSummary -Result $result
        return $result
    }
    $result.EngineOk = $true
    $result.EngineVersion = Protect-DoctorText (($engine.Output -split "`n")[0].Trim())
    Add-DoctorCheck $result 'Docker Engine' 'PASS' "可用（Server $($result.EngineVersion)）"

    # 2. 容器清单
    $inventory = Get-DoctorContainerInventory -Context $Context
    if (-not $inventory.Ok) {
        Add-DoctorCheck $result '实例容器' 'FAIL' $inventory.Error
        $result.RootCause = 'CONTAINER_INVENTORY_FAILED'
        $result.RecommendedAction = '先确认 docker ps 可用；然后运行 pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action doctor'
        $result.Summary = Get-ChenStackDoctorSummary -Result $result
        return $result
    }

    $expectedServices = @($script:DoctorInfraServices + $script:DoctorAppServices)
    $hasQdrant = @($inventory.Items | Where-Object { $_.Service -eq 'qdrant' }).Count -gt 0
    if ($hasQdrant) { $expectedServices += 'qdrant' }

    if ($inventory.Items.Count -eq 0) {
        Add-DoctorCheck $result '实例容器' 'FAIL' "实例 $($Context.Instance) 尚未创建任何容器"
        $result.MissingServices = $expectedServices
        $result.RootCause = 'CONTAINERS_NOT_CREATED'
        $result.RecommendedAction = "pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action init -Instance $($Context.Instance)"
        $result.Summary = Get-ChenStackDoctorSummary -Result $result
        return $result
    }

    $current = @()
    $historical = @()
    foreach ($service in $expectedServices) {
        $selection = Select-DoctorServiceContainer -Items $inventory.Items -Service $service
        if ($null -eq $selection.Selected) { $result.MissingServices += $service; continue }
        $current += [pscustomobject]@{
            Service = $service
            Name    = $selection.Selected.Name
            State   = $selection.Selected.State
            Running = $selection.Selected.Running
        }
        if (-not $selection.Selected.Running) { $result.StoppedServices += $service }
        if ($selection.Ambiguous) { $result.AmbiguousServices += $service }
        foreach ($item in $selection.Historical) {
            $historical += [pscustomobject]@{ Service = $service; Name = $item.Name; State = $item.State }
        }
    }
    $result.Containers = $current
    $result.Historical = $historical

    $runningCount = @($current | Where-Object { $_.Running }).Count
    $containerDetail = "$runningCount/$($expectedServices.Count) 运行中"
    if ($result.MissingServices.Count -gt 0) { $containerDetail += "；缺少：$($result.MissingServices -join ', ')" }
    if ($result.StoppedServices.Count -gt 0) { $containerDetail += "；未运行：$($result.StoppedServices -join ', ')" }
    if ($result.AmbiguousServices.Count -gt 0) { $containerDetail += "；重复运行：$($result.AmbiguousServices -join ', ')" }
    $containerStatus = if ($result.MissingServices.Count -eq 0 -and $result.StoppedServices.Count -eq 0 -and
        $result.AmbiguousServices.Count -eq 0) { 'PASS' } else { 'FAIL' }
    Add-DoctorCheck $result '实例容器' $containerStatus $containerDetail

    if ($historical.Count -gt 0) {
        Add-DoctorCheck $result '历史容器' 'WARN' ("已停止的历史容器（不作为正式服务）：" +
            (($historical | ForEach-Object { "$($_.Name)[$($_.State)]" }) -join ', '))
    } else {
        Add-DoctorCheck $result '历史容器' 'PASS' '无同项目历史容器'
    }

    # 3. 基础设施与 health
    $infraNames = @($current | Where-Object { $_.Service -in $script:DoctorInfraServices -or $_.Service -eq 'qdrant' } | ForEach-Object { $_.Name })
    $healthMap = Get-DoctorContainerHealth -Context $Context -Names $infraNames
    $infraDetail = @()
    foreach ($entry in $current) {
        if ($entry.Service -in $script:DoctorInfraServices -or $entry.Service -eq 'qdrant') {
            $health = 'none'
            if ($healthMap.ContainsKey($entry.Name)) { $health = $healthMap[$entry.Name] }
            $result.InfraStates[$entry.Service] = [pscustomobject]@{ Name = $entry.Name; Running = $entry.Running; Health = $health }
            if (-not $entry.Running) { $result.InfraOk = $false }
            $stateText = if ($entry.Running) { 'running' } else { $entry.State }
            $infraDetail += "$($entry.Service)=$stateText/$health"
        }
    }
    if ($result.MissingServices.Count -gt 0) {
        $missingInfra = @($result.MissingServices | Where-Object { $_ -in $script:DoctorInfraServices })
        if ($missingInfra.Count -gt 0) { $result.InfraOk = $false }
    }
    Add-DoctorCheck $result '基础设施' ($(if ($result.InfraOk) { 'PASS' } else { 'FAIL' })) ($infraDetail -join '; ')

    # 4. 后端容器
    $backendEntry = $current | Where-Object { $_.Service -eq 'backend' } | Select-Object -First 1
    if ($null -ne $backendEntry) {
        $result.BackendName = $backendEntry.Name
        $result.BackendRunning = [bool]$backendEntry.Running
    }
    $backendDetail = if ($result.BackendName) { "$($result.BackendName) = $($backendEntry.State)" } else { '未找到后端容器' }
    Add-DoctorCheck $result '后端容器' ($(if ($result.BackendRunning) { 'PASS' } else { 'FAIL' })) $backendDetail

    if (-not $result.BackendRunning) {
        $result.RootCause = if ($result.MissingServices -contains 'backend') { 'CONTAINERS_NOT_CREATED' } else { 'BACKEND_NOT_RUNNING' }
        $result.RecommendedAction = if ($result.RootCause -eq 'CONTAINERS_NOT_CREATED') {
            "pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action init -Instance $($Context.Instance)"
        } else {
            "pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action up -Instance $($Context.Instance)"
        }
        $result.Summary = Get-ChenStackDoctorSummary -Result $result
        return $result
    }

    # 5. 后端内部健康
    $internal = Test-DoctorBackendHealth -Context $Context -Name $result.BackendName
    $result.InternalHealth = $internal.Status
    $result.InternalDetail = $internal.Detail
    Add-DoctorCheck $result '后端内部健康' ($(if ($result.InternalHealth -eq 'UP') { 'PASS' } else { 'FAIL' })) $result.InternalDetail

    # 6. 端口发布与宿主机端口
    $facts = Get-DoctorBackendFacts -Context $Context -Name $result.BackendName
    $result.BackendFacts = $facts
    $targetPort = "$($Context.BackendContainerPort)/tcp"
    $hostBindings = @($facts.HostBindings | Where-Object { $_.Port -eq $targetPort })
    $expectedBindings = @($hostBindings | Where-Object {
            $_.HostPort -eq [string]$Context.BackendPort -and ($_.HostIp -in @('127.0.0.1', '::1', ''))
        })
    $result.HostBindingOk = ($expectedBindings.Count -gt 0)
    $hostBindingText = if ($hostBindings.Count -gt 0) {
        ($hostBindings | ForEach-Object { "$($_.Port) -> $($_.HostIp):$($_.HostPort)" }) -join '; '
    } else { '无 5000/tcp 绑定' }
    Add-DoctorCheck $result '端口发布配置' ($(if ($result.HostBindingOk) { 'PASS' } else { 'FAIL' })) $hostBindingText

    $effectiveBindings = @($facts.PublishedBindings | Where-Object {
            $_.Port -eq $targetPort -and $_.HostPort -eq [string]$Context.BackendPort -and ($_.HostIp -in @('127.0.0.1', '::1', ''))
        })
    $result.BackendPublished = ($effectiveBindings.Count -gt 0)
    $publishedText = if ($facts.PublishedBindings.Count -gt 0) {
        ($facts.PublishedBindings | ForEach-Object { "$($_.Port) -> $($_.HostIp):$($_.HostPort)" }) -join '; '
    } elseif ($facts.PortsKeyPresent) { "5000/tcp 存在但没有任何实际绑定（Docker 端口发布缺失）" }
    else { 'NetworkSettings.Ports 中没有 5000/tcp' }
    Add-DoctorCheck $result '实际端口发布' ($(if ($result.BackendPublished) { 'PASS' } else { 'FAIL' })) $publishedText
    $result.PublishDetail = $publishedText

    $owner = & $Context.PortOwner $Context.BackendPort
    $result.PortOwner = $owner
    $ownerText = if (-not $owner.Supported) { '当前系统不支持监听查询' }
    elseif ($owner.Listening) { "127.0.0.1:$($Context.BackendPort) 由 $($owner.ProcessName)($($owner.Pid)) 监听" }
    else { "127.0.0.1:$($Context.BackendPort) 未检测到监听进程" }
    $ownerStatus = if (-not $owner.Supported) { 'UNKNOWN' }
    elseif ($owner.Listening -and -not (Test-DoctorDockerOwnedProcess -ProcessName $owner.ProcessName)) { 'WARN' }
    elseif ($owner.Listening) { 'PASS' }
    else { 'WARN' }
    Add-DoctorCheck $result 'Windows 端口监听' $ownerStatus $ownerText

    foreach ($pair in @(@{ Name = '后端'; Port = $Context.BackendPort }, @{ Name = '管理端'; Port = $Context.AdminPort }, @{ Name = '用户端'; Port = $Context.UserPort })) {
        if ($pair.Port -eq $Context.BackendPort) { $result.PortOwners[$pair.Name] = $owner; continue }
        $result.PortOwners[$pair.Name] = & $Context.PortOwner $pair.Port
    }

    $frontendListenerDetail = @()
    $frontendListening = 0
    foreach ($pair in @(@{ Name = '管理端'; Port = $Context.AdminPort }, @{ Name = '用户端'; Port = $Context.UserPort })) {
        $frontendOwner = $result.PortOwners[$pair.Name]
        if (-not $frontendOwner.Supported) {
            $frontendListenerDetail += "$($pair.Name):$($pair.Port) 不支持查询"
        } elseif ($frontendOwner.Listening) {
            $frontendListening++
            $frontendListenerDetail += "$($pair.Name):$($pair.Port) 由 $($frontendOwner.ProcessName)($($frontendOwner.Pid)) 监听"
        } else {
            $frontendListenerDetail += "$($pair.Name):$($pair.Port) 未检测到监听（可能由 Docker 端口代理隐藏）"
        }
    }
    $frontendListenerStatus = if ($frontendListening -eq 2) { 'PASS' } elseif ($frontendListening -eq 0) { 'UNKNOWN' } else { 'WARN' }
    Add-DoctorCheck $result '前端端口监听' $frontendListenerStatus ($frontendListenerDetail -join '; ')

    $ranges = @(& $Context.ExcludedRanges)
    $result.ExcludedRanges = $ranges
    $inRange = @($ranges | Where-Object { $Context.BackendPort -ge $_.Start -and $Context.BackendPort -le $_.End })
    if ($inRange.Count -gt 0) {
        Add-DoctorCheck $result '端口排除范围' 'WARN' "$($Context.BackendPort) 位于 Windows 排除范围 $($inRange[0].Start)-$($inRange[0].End)（可能是端口发布失败的相关因素，未证实）"
    } else {
        Add-DoctorCheck $result '端口排除范围' 'PASS' "$($Context.BackendPort) 不在 Windows TCP 排除范围内"
    }

    # 7. 宿主机访问后端
    $hostUri = "http://127.0.0.1:$($Context.BackendPort)/actuator/health"
    $hostHttp = & $Context.HttpGet $hostUri $Context.HttpTimeoutSeconds
    $result.HostStatusCode = [int]$hostHttp.StatusCode
    $hostUp = ($hostHttp.Ok -and $hostHttp.Body -match '"status"\s*:\s*"UP"')
    $result.HostAccess = if ($hostUp) { 'PASS' } else { 'FAIL' }
    $result.HostDetail = if ($hostUp) { "HTTP $($hostHttp.StatusCode) / UP（$hostUri）" }
    elseif ($hostHttp.StatusCode -gt 0) { "HTTP $($hostHttp.StatusCode)，未返回 UP（$hostUri）" }
    else { "无法访问 $hostUri ：$(Protect-DoctorText $hostHttp.Error)" }
    Add-DoctorCheck $result '宿主机访问后端' $result.HostAccess $result.HostDetail

    # 8. 前端访问
    foreach ($frontend in @(@{ Service = 'frontend-user'; Port = $Context.UserPort; Label = '用户端' }, @{ Service = 'frontend-admin'; Port = $Context.AdminPort; Label = '管理端' })) {
        $entry = $current | Where-Object { $_.Service -eq $frontend.Service } | Select-Object -First 1
        $uri = "http://127.0.0.1:$($frontend.Port)/"
        if ($null -eq $entry -or -not $entry.Running) {
            $status = 'FAIL'
            $detail = if ($null -eq $entry) { "容器不存在（$uri）" } else { "容器未运行（$uri）" }
        } else {
            $http = & $Context.HttpGet $uri $Context.HttpTimeoutSeconds
            if ($http.Ok -and [int]$http.StatusCode -ge 200 -and [int]$http.StatusCode -lt 400) {
                $status = 'PASS'
                $detail = "HTTP $($http.StatusCode)（$uri）"
            } else {
                $status = 'FAIL'
                $detail = if ($http.StatusCode -gt 0) { "HTTP $($http.StatusCode)（$uri）" } else { "无法访问 $uri ：$(Protect-DoctorText $http.Error)" }
                $nginx = Invoke-DoctorDocker -Context $Context -Arguments @('exec', $entry.Name, 'curl', '-fsS', '-o', '/dev/null', 'http://127.0.0.1/')
                $nginxText = if ($nginx.ExitCode -eq 0) { '；容器内 Nginx 正常' } else { '；容器内 Nginx 也失败' }
                $detail += $nginxText
            }
        }
        if ($frontend.Service -eq 'frontend-user') {
            $result.FrontendUser = $status
            $result.FrontendUserDetail = $detail
        } else {
            $result.FrontendAdmin = $status
            $result.FrontendAdminDetail = $detail
        }
        Add-DoctorCheck $result "$($frontend.Label) HTTP" $status $detail
    }

    # 9. 前端请求目标一致性
    $targetDetails = @()
    $targetKnown = 0
    $targetMismatch = $false
    foreach ($frontend in @(@{ Service = 'frontend-user'; Label = '用户端' }, @{ Service = 'frontend-admin'; Label = '管理端' })) {
        $entry = $current | Where-Object { $_.Service -eq $frontend.Service } | Select-Object -First 1
        if ($null -eq $entry -or -not $entry.Running) { continue }
        $target = Get-DoctorRequestTarget -Context $Context -Name $entry.Name
        if ($null -eq $target.Consistent) {
            $targetDetails += "$($frontend.Label)：$($target.Detail)"
            continue
        }
        $targetKnown++
        if (-not $target.Consistent) { $targetMismatch = $true }
        $targetDetails += "$($frontend.Label)：$($target.Detail)（期望端口 $($Context.BackendPort)）"
    }
    if ($targetKnown -eq 0) {
        $result.RequestTarget = 'UNKNOWN'
        Add-DoctorCheck $result '前端请求目标' 'UNKNOWN' (($targetDetails -join '；') + '；未能从产物中提取 API 地址')
    } elseif ($targetMismatch) {
        $result.RequestTarget = 'MISMATCH'
        Add-DoctorCheck $result '前端请求目标' 'FAIL' ($targetDetails -join '；')
    } else {
        $result.RequestTarget = 'CONSISTENT'
        Add-DoctorCheck $result '前端请求目标' 'PASS' ($targetDetails -join '；')
    }
    $result.RequestTargetDetail = ($targetDetails -join '；')

    # 10. 容器网络互通
    $networkNames = @($current | ForEach-Object { $_.Name })
    $networkCall = Invoke-DoctorDocker -Context $Context -Arguments (@('inspect') + $networkNames + @('--format', '{{json .NetworkSettings.Networks}}'))
    if ($networkCall.ExitCode -eq 0) {
        $rows = @($networkCall.Output -split "`n" | Where-Object { $_.Trim() })
        $networkSets = @()
        foreach ($row in $rows) {
            $map = ConvertFrom-DoctorJson -Text $row
            if ($map -is [System.Collections.IDictionary]) { $networkSets += , @($map.Keys) }
        }
        $shared = @()
        if ($networkSets.Count -gt 0) {
            $shared = @($networkSets[0] | Where-Object { $name = $_; @($networkSets | Where-Object { $_ -notcontains $name }).Count -eq 0 })
        }
        $dns = Invoke-DoctorDocker -Context $Context -Arguments @('exec', $result.BackendName, 'getent', 'hosts', 'mysql')
        $dnsText = if ($dns.ExitCode -eq 0) { 'backend 可解析 mysql' } else { 'backend 无法解析 mysql' }
        if ($shared.Count -gt 0 -and $dns.ExitCode -eq 0) {
            $result.NetworkOk = 'PASS'
            $result.NetworkDetail = "共用网络 $($shared -join ', ')；$dnsText"
        } else {
            $result.NetworkOk = 'FAIL'
            $result.NetworkDetail = "共用网络 $($shared -join ', ')；$dnsText"
        }
    } else {
        $result.NetworkOk = 'UNKNOWN'
        $result.NetworkDetail = '无法读取容器网络信息'
    }
    Add-DoctorCheck $result '容器网络互通' ($(if ($result.NetworkOk -eq 'PASS') { 'PASS' } elseif ($result.NetworkOk -eq 'FAIL') { 'FAIL' } else { 'UNKNOWN' })) $result.NetworkDetail

    # 11. 受控发布边界
    $evidence = @(Get-DoctorReleaseEvidence -Facts $facts)
    $result.ReleaseEvidence = $evidence
    $result.ReleaseManaged = ($evidence.Count -gt 0)
    $releaseStatus = if ($result.ReleaseManaged) { 'WARN' } else { 'PASS' }
    $releaseDetail = if ($result.ReleaseManaged) {
        '受控发布实例（' + ($evidence -join '；') + '）：repair-ports 需显式允许重启'
    } else { '普通实例（未检测到受控发布标记）' }
    Add-DoctorCheck $result '受控发布边界' $releaseStatus $releaseDetail

    # 12. 结论
    $foreignOwner = ($owner.Supported -and $owner.Listening -and -not (Test-DoctorDockerOwnedProcess -ProcessName $owner.ProcessName))
    $backendContainerProblem = ($result.MissingServices -contains 'backend') -or (-not $result.BackendRunning)
    $infraProblem = -not $result.InfraOk

    if ($backendContainerProblem) {
        $result.RootCause = 'BACKEND_NOT_RUNNING'
        $result.RecommendedAction = "pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action up -Instance $($Context.Instance)"
    } elseif ($infraProblem) {
        $result.RootCause = 'INFRASTRUCTURE_NOT_RUNNING'
        $result.RecommendedAction = "pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action up -Instance $($Context.Instance)；仍失败时查看 docker ps -a 与 docker logs --tail 120 chen-stack-mysql"
    } elseif ($result.InternalHealth -ne 'UP') {
        $result.RootCause = 'BACKEND_INTERNAL_HEALTH_DOWN'
        $result.RecommendedAction = "docker logs --tail 200 $($result.BackendName)；核对 MySQL/Redis 后运行 pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action up -Instance $($Context.Instance)"
    } elseif (-not $result.HostBindingOk) {
        $result.RootCause = 'PORT_CONFIG_MISMATCH'
        $result.RecommendedAction = if ($result.ReleaseManaged) {
            '受控发布实例的端口配置来自发布清单：按 script/release/README.md 处理，勿用普通 upgrade 重建容器'
        } else {
            "pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action upgrade -SkipBuild -Instance $($Context.Instance)"
        }
    } elseif (-not $result.BackendPublished) {
        if ($result.HostAccess -eq 'PASS') {
            $result.RootCause = 'PORT_PUBLISH_UNVERIFIED'
            $result.RecommendedAction = '端口绑定信息缺失但宿主机可访问，可能由端口代理接管：先记录现场，再运行 pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action doctor 复核'
        } elseif ($foreignOwner) {
            $result.RootCause = 'WINDOWS_PORT_CONFLICT'
            $result.RecommendedAction = "先确认占用：Get-NetTCPConnection -State Listen -LocalPort $($Context.BackendPort) | Select-Object LocalAddress,LocalPort,OwningProcess；停止占用进程后运行 pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action repair-ports -Apply -Instance $($Context.Instance)"
        } else {
            $result.RootCause = 'DOCKER_PORT_PUBLISH_MISSING'
            $result.RecommendedAction = "pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action repair-ports -Instance $($Context.Instance)（先诊断）；确认后运行 pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action repair-ports -Apply -Instance $($Context.Instance)"
        }
    } elseif ($result.HostAccess -ne 'PASS') {
        if ($foreignOwner) {
            $result.RootCause = 'WINDOWS_PORT_CONFLICT'
            $result.RecommendedAction = "Get-NetTCPConnection -State Listen -LocalPort $($Context.BackendPort) | Select-Object LocalAddress,LocalPort,OwningProcess；释放端口后运行 pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action doctor -Instance $($Context.Instance)"
        } else {
            $result.RootCause = 'BACKEND_HOST_HTTP_FAILED'
            $result.RecommendedAction = "docker restart --timeout 30 $($result.BackendName)；然后运行 pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action doctor -Instance $($Context.Instance)"
        }
    } elseif ($result.FrontendUser -ne 'PASS' -or $result.FrontendAdmin -ne 'PASS') {
        $result.RootCause = 'FRONTEND_NGINX_UNREACHABLE'
        $result.RecommendedAction = "docker logs --tail 100 chen-stack-user；docker logs --tail 100 chen-stack-admin；然后运行 pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action restart -Instance $($Context.Instance)"
    } elseif ($result.RequestTarget -eq 'MISMATCH') {
        $result.RootCause = 'FRONTEND_REQUEST_TARGET_MISMATCH'
        $result.RecommendedAction = if ($result.ReleaseManaged) {
            '受控发布实例：按 script/release/README.md 用与发布清单一致的制品重建前端'
        } else {
            "pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action upgrade -Instance $($Context.Instance)（重新构建前端并重建应用容器）"
        }
    } else {
        $result.RootCause = 'NONE'
        $result.RecommendedAction = '无需操作；保持当前运行状态'
    }

    if ($result.RootCause -in @('DOCKER_PORT_PUBLISH_MISSING', 'PORT_PUBLISH_UNVERIFIED', 'WINDOWS_PORT_CONFLICT', 'BACKEND_HOST_HTTP_FAILED')) {
        $causes = @(
            'Docker Desktop 端口代理（com.docker.backend / docker-proxy）在容器运行后未建立实际绑定；',
            'Windows 端口保留/排除范围或第三方进程占用了宿主端口；',
            '宿主网络栈（WSL2 / WinNAT）状态异常导致发布未生效。'
        )
        if ($inRange.Count -gt 0) {
            $causes += "本次检测到 $($Context.BackendPort) 位于 Windows 排除范围 $($inRange[0].Start)-$($inRange[0].End)。"
        }
        if ($foreignOwner) {
            $causes += "本次检测到端口被 $($owner.ProcessName)($($owner.Pid)) 占用。"
        }
        $causes += '以上只是可能原因，本次诊断未证实具体触发点。'
        $result.PossibleCauses = $causes
    }

    if ($result.RootCause -ne 'NONE') {
        $result.LogFindings = @(Get-DoctorLogFindings -Context $Context -Name $result.BackendName)
    }

    $criticalFailures = @($result.Checks | Where-Object { $_.Status -eq 'FAIL' }).Count
    $result.Ok = ($criticalFailures -eq 0) -and ($result.RootCause -eq 'NONE')
    $result.Summary = Get-ChenStackDoctorSummary -Result $result
    return $result
}

# ---------------------------------------------------------------- 摘要与展示

function Get-ChenStackDoctorSummary {
    <#
    .SYNOPSIS
        生成固定格式摘要（可直接粘贴给他人或用于现场记录）。
    #>
    param([Parameter(Mandatory)]$Result)

    $backendPort = 5000
    if ($null -ne $Result.PSObject.Properties['BackendPort']) { $backendPort = [int]$Result.BackendPort }
    $port5000 = if ($backendPort -eq 5000) {
        if ($Result.BackendPublished) { 'YES' } else { 'NO' }
    } else { 'N/A' }

    $lines = @()
    $lines += "CHEN_STACK_DOCTOR=$(if ($Result.Ok) { 'PASS' } else { 'FAIL' })"
    $lines += "BACKEND_INTERNAL_HEALTH=$($Result.InternalHealth)"
    $lines += "BACKEND_HOST_ACCESS=$($Result.HostAccess)"
    $lines += "PORT_5000_PUBLISHED=$port5000"
    if ($backendPort -ne 5000) {
        $lines += "PORT_$($backendPort)_PUBLISHED=$(if ($Result.BackendPublished) { 'YES' } else { 'NO' })"
    }
    $lines += "FRONTEND_USER=$($Result.FrontendUser)"
    $lines += "FRONTEND_ADMIN=$($Result.FrontendAdmin)"
    $lines += "FRONTEND_REQUEST_TARGET=$($Result.RequestTarget)"
    $lines += "ROOT_CAUSE=$($Result.RootCause)"
    $lines += "RECOMMENDED_ACTION=$($Result.RecommendedAction)"
    $lines += "INSTANCE=$($Result.Instance)"
    $lines += "BACKEND_PORT=$backendPort"
    $lines += "RELEASE_MANAGED=$(if ($Result.ReleaseManaged) { 'YES' } else { 'NO' })"
    return $lines
}

function Format-ChenStackDoctorReport {
    <#
    .SYNOPSIS
        打印人类可读的诊断报告。
    #>
    param([Parameter(Mandatory)]$Result)

    $width = 72
    Write-Host ''
    Write-Host ('=' * $width) -ForegroundColor DarkCyan
    Write-Host "  辰栈 Docker 部署诊断（实例 $($Result.Instance)）" -ForegroundColor Cyan
    Write-Host ('=' * $width) -ForegroundColor DarkCyan

    foreach ($check in $Result.Checks) {
        $color = switch ($check.Status) {
            'PASS' { 'Green' }
            'WARN' { 'Yellow' }
            'FAIL' { 'Red' }
            default { 'DarkGray' }
        }
        $marker = switch ($check.Status) {
            'PASS' { 'PASS' }
            'WARN' { 'WARN' }
            'FAIL' { 'FAIL' }
            default { 'INFO' }
        }
        Write-Host ('  {0,-4} {1,-18} {2}' -f $marker, $check.Label, $check.Detail) -ForegroundColor $color
    }

    if ($Result.Historical.Count -gt 0) {
        Write-Host ''
        Write-Host '  历史容器（已停止，不作为正式服务）：' -ForegroundColor DarkGray
        foreach ($item in $Result.Historical) {
            Write-Host "    $($item.Name)  [$($item.State)]" -ForegroundColor DarkGray
        }
    }

    if ($Result.LogFindings.Count -gt 0) {
        Write-Host ''
        Write-Host '  最近日志中的关键错误（已脱敏）：' -ForegroundColor Yellow
        foreach ($line in $Result.LogFindings) { Write-Host "    $line" -ForegroundColor DarkYellow }
    }

    if ($Result.PossibleCauses.Count -gt 0) {
        Write-Host ''
        Write-Host '  可能原因（未证实，不代表本次结论）：' -ForegroundColor Yellow
        foreach ($cause in $Result.PossibleCauses) { Write-Host "    - $cause" -ForegroundColor DarkYellow }
    }

    Write-Host ''
    Write-Host '  结论' -ForegroundColor Cyan
    Write-Host "    ROOT_CAUSE=$($Result.RootCause)"
    Write-Host "    下一步：$($Result.RecommendedAction)" -ForegroundColor Yellow
    Write-Host ''
}

function Set-ChenStackDoctorClipboard {
    <# 复制摘要并回读校验；失败不影响诊断结论。 #>
    param([string[]]$Lines)

    $text = ($Lines -join "`n")
    try {
        Set-Clipboard -Value $text -ErrorAction Stop
        Start-Sleep -Milliseconds 80
        $back = Get-Clipboard -Raw -ErrorAction Stop
        if ([string]::IsNullOrEmpty($back)) { return @{ Status = 'FAILED'; Detail = '回读为空' } }
        $normalized = ($back -replace "`r`n", "`n").TrimEnd("`n")
        if ($normalized -ceq $text) { return @{ Status = 'COPIED_VERIFIED'; Detail = '' } }
        return @{ Status = 'COPIED_UNVERIFIED'; Detail = '回读内容与摘要不一致' }
    } catch {
        return @{ Status = 'FAILED'; Detail = Protect-DoctorText $_.Exception.Message }
    }
}

# ---------------------------------------------------------------- 安全恢复

function Invoke-ChenStackPortRepair {
    <#
    .SYNOPSIS
        受控恢复后端端口发布：仅在容器内健康 UP、宿主机端口绑定缺失时重启同一容器。

.DESCRIPTION
    只使用 docker restart --timeout 30，不重建容器、不换镜像、不删卷、不改标签。
    默认只诊断；-Apply 才执行。受控发布实例需要额外的 -AllowReleaseRestart。
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Context,
        [switch]$Apply,
        [switch]$AllowReleaseRestart,
        [ValidateRange(1, 3)][int]$MaxAttempts = 2,
        [int]$ProbeTimeoutSeconds = 120,
        [int]$ProbeIntervalSeconds = 5
    )

    $doctor = Invoke-ChenStackDoctor -Context $Context
    $outcome = 'REFUSED'
    $reasons = @()
    $attempts = 0
    $verifications = @()
    $snapshot = $null
    $post = $null
    $summary = @()

    if ($doctor.Ok) {
        $outcome = 'NOT_NEEDED'
        $reasons += '所有检查通过，无需恢复'
    } elseif ($doctor.RootCause -ne 'DOCKER_PORT_PUBLISH_MISSING') {
        $reasons += "当前 ROOT_CAUSE=$($doctor.RootCause)，不属于「内部健康 UP 但宿主机端口发布缺失」，不允许自动重启"
        $reasons += '请先按 doctor 的 RECOMMENDED_ACTION 处理对应故障'
    } elseif (-not $doctor.BackendRunning) {
        $reasons += '后端容器未运行：请先执行 -Action up'
    } elseif ($doctor.InternalHealth -ne 'UP') {
        $reasons += '后端内部健康不是 UP：重启容器不能修复 Java/依赖问题'
    } elseif (-not $doctor.HostBindingOk) {
        $reasons += 'HostConfig 中没有 127.0.0.1 端口绑定：需要重建应用容器，不能用重启修复'
    } elseif ($doctor.PortOwner.Supported -and $doctor.PortOwner.Listening -and
        -not (Test-DoctorDockerOwnedProcess -ProcessName $doctor.PortOwner.ProcessName)) {
        $reasons += "端口 $($Context.BackendPort) 被 $($doctor.PortOwner.ProcessName)($($doctor.PortOwner.Pid)) 占用：先释放端口"
    } else {
        $outcome = 'DRY_RUN'
        $reasons += "可恢复：容器内健康 UP，HostConfig 有端口绑定，但 NetworkSettings 无实际发布，宿主机 HTTP 不可达"
    }

    if ($doctor.ReleaseManaged) {
        $reasons += '检测到受控发布实例（' + ($doctor.ReleaseEvidence -join '；') + '）'
        if ($Apply -and $outcome -eq 'DRY_RUN' -and -not $AllowReleaseRestart) {
            $outcome = 'REFUSED'
            $reasons += '受控发布实例只允许重启同一个容器，不重建、不改镜像或挂载；确认现场后加 -AllowReleaseRestart 才会执行'
        } elseif (-not $AllowReleaseRestart) {
            $reasons += '实际执行需要同时加 -Apply 与 -AllowReleaseRestart'
        }
    }

    if ($outcome -eq 'DRY_RUN' -and -not $Apply) {
        $reasons += '当前为默认诊断模式（未执行任何写操作）'
    }

    if ($outcome -in @('REFUSED', 'NOT_NEEDED')) {
        $summary = Get-ChenStackRepairSummary -Outcome $outcome -Attempts 0 -Doctor $doctor -ImageUnchanged 'N/A' -StillRunning 'N/A'
        return [pscustomobject]@{
            Outcome = $outcome; Reasons = $reasons; Attempts = 0; Doctor = $doctor; Post = $null
            Snapshot = $null; Verifications = @(); Preview = @(); Summary = $summary
        }
    }

    if (-not $Apply) {
        # 只诊断：给出将执行的命令与确认方式
        $summary = Get-ChenStackRepairSummary -Outcome 'DRY_RUN' -Attempts 0 -Doctor $doctor -ImageUnchanged 'N/A' -StillRunning 'N/A'
        return [pscustomobject]@{
            Outcome = 'DRY_RUN'; Reasons = $reasons; Attempts = 0; Doctor = $doctor; Post = $null
            Snapshot = $null; Verifications = @()
            Preview = @("docker restart --timeout 30 $($doctor.BackendName)") ; Summary = $summary
        }
    }

    $snapshot = [pscustomobject]@{
        Name        = $doctor.BackendName
        Id          = ''
        ImageId     = ''
        RunPolicy   = ''
        Mounts      = @($doctor.BackendFacts.Mounts | ForEach-Object { "$($_.Destination)|$($_.Source)|$($_.RW)" } | Sort-Object)
        PortsDetail = $doctor.PublishDetail
    }
    $initialState = Get-DoctorContainerState -Context $Context -Names @($doctor.BackendName)
    if ($initialState.ContainsKey($doctor.BackendName)) {
        $snapshot.ImageId = $initialState[$doctor.BackendName].ImageId
        $snapshot.Id = $initialState[$doctor.BackendName].Id
    }

    $succeeded = $false
    for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
        $attempts = $attempt
        $restart = Invoke-DoctorDocker -Context $Context -Arguments @('restart', '--timeout', '30', $doctor.BackendName) -AllowWrite
        if ($restart.ExitCode -ne 0) {
            $reasons += "docker restart 失败（exit=$($restart.ExitCode)）：$(Protect-DoctorText (@($restart.Output -split "`n")[0]))"
            break
        }
        $verification = Wait-DoctorRepairVerification -Context $Context -Snapshot $snapshot -TimeoutSeconds $ProbeTimeoutSeconds -IntervalSeconds $ProbeIntervalSeconds
        $verifications += $verification
        if ($verification.Ok) {
            $succeeded = $true
            break
        }
        $reasons += "第 $attempt 次重启后仍未恢复：$($verification.Detail)"
        if ($verification.ImageUnchanged -eq 'NO') {
            $reasons += '检测到镜像身份变化：为避免破坏受控发布链，立即停止重试'
            break
        }
        if ($attempt -lt $MaxAttempts) {
            $sleepMs = if ($ProbeIntervalSeconds -gt 0) { $ProbeIntervalSeconds * 1000 } else { 200 }
            Start-Sleep -Milliseconds $sleepMs
        }
    }

    $post = Invoke-ChenStackDoctor -Context $Context
    if (-not $succeeded) {
        $outcome = 'FAILED'
        $reasons += '已达到重试上限：保留当前现场，未删除任何容器、镜像或数据'
    } else {
        $outcome = 'SUCCESS'
    }

    $lastVerification = if ($verifications.Count -gt 0) { $verifications[-1] } else { $null }
    $imageUnchanged = if ($null -ne $lastVerification) { $lastVerification.ImageUnchanged } else { 'N/A' }
    $stillRunning = if ($null -ne $lastVerification) { $lastVerification.StillRunning } else { 'N/A' }
    $summary = Get-ChenStackRepairSummary -Outcome $outcome -Attempts $attempts -Doctor $post -ImageUnchanged $imageUnchanged -StillRunning $stillRunning

    return [pscustomobject]@{
        Outcome      = $outcome
        Reasons      = $reasons
        Attempts     = $attempts
        Doctor       = $doctor
        Post         = $post
        Snapshot     = $snapshot
        Verifications = $verifications
        Preview      = @()
        Summary      = $summary
    }
}

function Wait-DoctorRepairVerification {
    <# 重启后在限定时间内核对：容器运行、镜像未变、端口发布存在、内部与宿主机健康 UP。 #>
    param(
        [Parameter(Mandatory)]$Context,
        [Parameter(Mandatory)]$Snapshot,
        [int]$TimeoutSeconds = 120,
        [int]$IntervalSeconds = 5
    )

    $deadline = (Get-Date).AddSeconds([Math]::Max(1, $TimeoutSeconds))
    $sleepMs = if ($IntervalSeconds -gt 0) { $IntervalSeconds * 1000 } else { 100 }
    $last = [pscustomobject]@{
        Ok = $false; Running = $false; StillRunning = 'N/A'; ImageUnchanged = 'N/A'; MountsUnchanged = 'N/A'
        Published = $false; InternalHealth = 'UNKNOWN'; HostAccess = 'FAIL'; Detail = '尚未验证'
    }

    do {
        $state = Get-DoctorContainerState -Context $Context -Names @($Snapshot.Name)
        $facts = Get-DoctorBackendFacts -Context $Context -Name $Snapshot.Name
        $internal = Test-DoctorBackendHealth -Context $Context -Name $Snapshot.Name
        $http = & $Context.HttpGet "http://127.0.0.1:$($Context.BackendPort)/actuator/health" $Context.HttpTimeoutSeconds

        $running = $false
        $imageId = ''
        if ($state.ContainsKey($Snapshot.Name)) {
            $running = [bool]$state[$Snapshot.Name].Running
            $imageId = [string]$state[$Snapshot.Name].ImageId
        }
        $targetPort = "$($Context.BackendContainerPort)/tcp"
        $published = @($facts.PublishedBindings | Where-Object {
                $_.Port -eq $targetPort -and $_.HostPort -eq [string]$Context.BackendPort
            }).Count -gt 0
        $mounts = @($facts.Mounts | ForEach-Object { "$($_.Destination)|$($_.Source)|$($_.RW)" } | Sort-Object)
        $mountsUnchanged = (($mounts -join ';') -ceq (@($Snapshot.Mounts) -join ';'))
        $imageUnchanged = if ($Snapshot.ImageId) { $imageId -eq $Snapshot.ImageId } else { $null }
        $hostUp = ($http.Ok -and $http.Body -match '"status"\s*:\s*"UP"')

        $last.Running = $running
        $last.StillRunning = if ($running) { 'YES' } else { 'NO' }
        $last.ImageUnchanged = if ($null -eq $imageUnchanged) { 'N/A' } elseif ($imageUnchanged) { 'YES' } else { 'NO' }
        $last.MountsUnchanged = if ($mountsUnchanged) { 'YES' } else { 'NO' }
        $last.Published = $published
        $last.InternalHealth = $internal.Status
        $last.HostAccess = if ($hostUp) { 'PASS' } else { 'FAIL' }
        $last.Ok = ($running -and $imageUnchanged -ne $false -and $mountsUnchanged -and $published -and
            $internal.Status -eq 'UP' -and $hostUp)
        if ($last.Ok) {
            $last.Detail = "镜像未变、容器运行、端口已发布、内部与宿主机健康 UP"
            return $last
        }
        $last.Detail = "running=$running; image=$($last.ImageUnchanged); mounts=$($last.MountsUnchanged); published=$published; internal=$($internal.Status); host=$($last.HostAccess)"
        if (-not $running) { break }
        Start-Sleep -Milliseconds $sleepMs
    } while ((Get-Date) -lt $deadline)

    return $last
}

function Get-ChenStackRepairSummary {
    param([string]$Outcome, [int]$Attempts, $Doctor, [string]$ImageUnchanged, [string]$StillRunning)

    $posted = if ($Doctor) { $Doctor.BackendPublished } else { $false }
    $lines = @()
    $lines += "CHEN_STACK_REPAIR=$Outcome"
    $lines += "REPAIR_ATTEMPTS=$Attempts"
    $lines += "IMAGE_IDENTITY_UNCHANGED=$ImageUnchanged"
    $lines += "CONTAINER_STILL_RUNNING=$StillRunning"
    $lines += "PORT_5000_PUBLISHED=$(if ($posted) { 'YES' } else { 'NO' })"
    $lines += "BACKEND_HOST_ACCESS=$(if ($Doctor) { $Doctor.HostAccess } else { 'FAIL' })"
    $lines += "ROOT_CAUSE=$(if ($Doctor) { $Doctor.RootCause } else { 'UNKNOWN' })"
    $lines += "NEXT_STEP=$(if ($Doctor) { $Doctor.RecommendedAction } else { 'pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action doctor' })"
    return $lines
}

function Format-ChenStackRepairReport {
    param([Parameter(Mandatory)]$Repair)

    Write-Host ''
    Write-Host ('=' * 72) -ForegroundColor DarkCyan
    Write-Host "  辰栈端口发布安全恢复（CHEN_STACK_REPAIR=$($Repair.Outcome)）" -ForegroundColor Cyan
    Write-Host ('=' * 72) -ForegroundColor DarkCyan
    foreach ($reason in $Repair.Reasons) {
        $color = if ($Repair.Outcome -in @('FAILED', 'REFUSED')) { 'Yellow' } else { 'Gray' }
        Write-Host "  - $reason" -ForegroundColor $color
    }
    if ($null -ne $Repair.Snapshot) {
        $snapshot = $Repair.Snapshot
        $containerId = if ($snapshot.Id) { $snapshot.Id.Substring(0, [Math]::Min(12, $snapshot.Id.Length)) } else { '未知' }
        $imageId = if ($snapshot.ImageId) { $snapshot.ImageId.Substring(0, [Math]::Min(19, $snapshot.ImageId.Length)) } else { '未知' }
        Write-Host ''
        Write-Host '  恢复前快照（用于重启后比对）' -ForegroundColor Cyan
        Write-Host "    容器：$($snapshot.Name)（$containerId）"
        Write-Host "    镜像：$imageId"
        Write-Host "    挂载：$($snapshot.Mounts.Count) 项"
        Write-Host "    端口：$($snapshot.PortsDetail)"
    }
    if ($Repair.Preview.Count -gt 0) {
        Write-Host ''
        Write-Host '  将执行的命令（需要人工确认）：' -ForegroundColor Yellow
        foreach ($command in $Repair.Preview) { Write-Host "    $command" -ForegroundColor DarkYellow }
        Write-Host ''
        Write-Host '  确认后执行：' -ForegroundColor Yellow
        Write-Host '    pwsh -NoProfile -File ./script/deploy/ChenStack.ps1 -Action repair-ports -Apply' -ForegroundColor DarkYellow
    }
    Write-Host ''
}

