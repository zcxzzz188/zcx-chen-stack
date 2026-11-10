#requires -Version 7.0
<#
.SYNOPSIS
    测试替身：按场景数据模拟 docker / HTTP，不接触真实 Docker 与真实端口。

.DESCRIPTION
    只被 script/deploy/tests 下的测试使用。生产部署脚本不读取本文件。
    场景对象是一个 hashtable：

        @{
          engine      = @{ ok = $true; version = '29.5.2' }
          containers  = @( <container> )
          http        = @{ '<port>' = @{ status = 200; body = '...' } }
          portOwners  = @{ '<port>' = @{ Supported = $true; Listening = $true; Pid = 1; ProcessName = 'other' } }
          excluded    = @( @{ Start = 5000; End = 5059; Administered = $false } )
          dns         = @{ '<container>' = @{ mysql = '172.18.0.1' } }
          repairEffect= 'fix' | 'none' | 'image-change'
        }

    <container> 由 New-FakeContainer 生成。
#>

function New-FakeContainer {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Service,
        [Parameter(Mandatory)][string]$Project,
        [string]$State = 'running',
        [bool]$Running = $true,
        [hashtable]$HostBindings = @{},
        [hashtable]$PublishedBindings = @{},
        [string]$ImageId = 'sha256:1111111111111111111111111111111111111111111111111111111111111111',
        [string]$ImageName = '',
        [string]$RestartPolicy = 'unless-stopped',
        [object[]]$Mounts = @(),
        [hashtable]$ExtraLabels = @{},
        [string[]]$Env = @('TZ=Asia/Shanghai'),
        [string]$Health = 'none',
        [string]$InternalHealth = 'UP',
        [string]$BackendUrl = '',
        [string]$Logs = ''
    )

    $labels = @{
        'com.docker.compose.project' = $Project
        'com.docker.compose.service' = $Service
        'com.docker.compose.version' = '2.0.0'
    }
    foreach ($key in $ExtraLabels.Keys) { $labels[$key] = $ExtraLabels[$key] }

    return @{
        name              = $Name
        project           = $Project
        service           = $Service
        state             = $State
        running           = $Running
        imageId           = $ImageId
        imageName         = if ($ImageName) { $ImageName } else { $Name }
        restartPolicy     = $RestartPolicy
        hostBindings      = $HostBindings
        publishedBindings = $PublishedBindings
        mounts            = @($Mounts)
        labels            = $labels
        env               = @($Env)
        health            = $Health
        internalHealth    = $InternalHealth
        backendUrl        = $BackendUrl
        logs              = $Logs
    }
}

function New-FakeScenario {
    <# 生成一个“全部正常”的实例场景；测试再按需破坏其中一项。 #>
    [CmdletBinding()]
    param(
        [string]$Instance = 'dev',
        [int]$BackendPort = 5000,
        [int]$AdminPort = 8000,
        [int]$UserPort = 7000,
        [string]$ApiUrl = ''
    )

    if (-not $ApiUrl) { $ApiUrl = "http://127.0.0.1:$BackendPort" }
    $hostBinding = {
        param([int]$HostPort, [int]$ContainerPort)
        return @{ "$ContainerPort/tcp" = @(@{ HostIp = '127.0.0.1'; HostPort = [string]$HostPort }) }
    }

    $containers = @(
        (New-FakeContainer -Name 'chen-stack-mysql' -Service 'mysql' -Project $Instance `
                -HostBindings (& $hostBinding 3308 3306) -PublishedBindings (& $hostBinding 3308 3306) -Health 'healthy' `
                -Mounts @(@{ Destination = '/var/lib/mysql'; Source = 'chen-stack-mysql-data'; RW = $true; Type = 'volume' })),
        (New-FakeContainer -Name 'chen-stack-redis' -Service 'redis' -Project $Instance `
                -HostBindings (& $hostBinding 6379 6379) -PublishedBindings (& $hostBinding 6379 6379) -Health 'healthy' `
                -Mounts @(@{ Destination = '/data'; Source = 'chen-stack-redis-data'; RW = $true; Type = 'volume' })),
        (New-FakeContainer -Name 'chen-stack-minio' -Service 'minio' -Project $Instance `
                -HostBindings (& $hostBinding 9000 9000) -PublishedBindings (& $hostBinding 9000 9000) -Health 'healthy'),
        (New-FakeContainer -Name 'chen-stack-rabbitmq' -Service 'rabbitmq' -Project $Instance `
                -HostBindings (& $hostBinding 5672 5672) -PublishedBindings (& $hostBinding 5672 5672) -Health 'healthy'),
        (New-FakeContainer -Name 'chen-stack-backend' -Service 'backend' -Project $Instance `
                -HostBindings (& $hostBinding $BackendPort 5000) -PublishedBindings (& $hostBinding $BackendPort 5000) `
                -RestartPolicy 'unless-stopped' `
                -Mounts @(
            @{ Destination = '/ai-usage'; Source = "$Instance-ai-usage"; RW = $true; Type = 'bind' },
            @{ Destination = '/release'; Source = "$Instance-release"; RW = $false; Type = 'bind' }
        ) -Env @('TZ=Asia/Shanghai', 'SPRING_PROFILES_ACTIVE=dev', 'MYSQL_PASSWORD=NotARealSecret123')),
        (New-FakeContainer -Name 'chen-stack-admin' -Service 'frontend-admin' -Project $Instance `
                -HostBindings (& $hostBinding $AdminPort 80) -PublishedBindings (& $hostBinding $AdminPort 80) -BackendUrl $ApiUrl),
        (New-FakeContainer -Name 'chen-stack-user' -Service 'frontend-user' -Project $Instance `
                -HostBindings (& $hostBinding $UserPort 80) -PublishedBindings (& $hostBinding $UserPort 80) -BackendUrl $ApiUrl)
    )

    return @{
        instance     = $Instance
        engine       = @{ ok = $true; version = '29.5.2' }
        containers   = $containers
        http         = @{
            "$BackendPort" = @{ status = 200; body = '{"status":"UP","groups":["liveness","readiness"]}' }
            "$AdminPort"   = @{ status = 200; body = '<html><body>admin</body></html>' }
            "$UserPort"    = @{ status = 200; body = '<html><body>user</body></html>' }
        }
        portOwners   = @{}
        excluded     = @()
        dns          = @{ 'chen-stack-backend' = @{ mysql = '172.18.0.7' } }
        repairEffect = 'fix'
    }
}

function Get-FakeContainer {
    param([hashtable]$Scenario, [string]$Name)
    return @($Scenario.containers | Where-Object { $_.name -eq $Name }) | Select-Object -First 1
}

function Invoke-FakeDocker {
    <# 模拟部署脚本会用到的 docker 子命令；未知命令返回 92，便于发现脚本漂移。 #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][hashtable]$Scenario,
        [Parameter(Mandatory)][string[]]$Arguments,
        [string]$CallLog = ''
    )

    if ($CallLog) {
        Add-Content -LiteralPath $CallLog -Value ($Arguments -join ' ') -Encoding utf8
    }
    if ($Arguments.Count -eq 0 -or [string]::IsNullOrWhiteSpace($Arguments[0])) {
        return @{ ExitCode = 1; Output = 'fake-docker: empty command' }
    }

    $verb = $Arguments[0]
    switch ($verb) {
        'version' {
            if (-not $Scenario.engine.ok) {
                return @{ ExitCode = 1; Output = 'Cannot connect to the Docker daemon at npipe:////./pipe/docker_engine. Is the docker daemon running?' }
            }
            return @{ ExitCode = 0; Output = [string]$Scenario.engine.version }
        }
        'ps' {
            $project = ''
            $service = ''
            for ($index = 1; $index -lt $Arguments.Count - 1; $index++) {
                if ($Arguments[$index] -ne '--filter') { continue }
                $filterValue = $Arguments[$index + 1]
                if ($filterValue -like 'label=com.docker.compose.project=*') {
                    $project = $filterValue.Substring('label=com.docker.compose.project='.Length)
                } elseif ($filterValue -like 'label=com.docker.compose.service=*') {
                    $service = $filterValue.Substring('label=com.docker.compose.service='.Length)
                }
            }
            $list = @($Scenario.containers)
            if ($project) { $list = @($list | Where-Object { $_.project -eq $project }) }
            if ($service) { $list = @($list | Where-Object { $_.service -eq $service }) }
            return @{ ExitCode = 0; Output = (@($list | ForEach-Object { $_.name }) -join "`n") }
        }
        'inspect' {
            $formatIndex = [Array]::IndexOf([object[]]$Arguments, '--format')
            if ($formatIndex -lt 1) { return @{ ExitCode = 1; Output = 'fake-docker: inspect 需要 --format' } }
            $format = $Arguments[$formatIndex + 1]
            $names = @($Arguments[1..($formatIndex - 1)])
            $lines = @()
            foreach ($name in $names) {
                $container = Get-FakeContainer -Scenario $Scenario -Name $name
                if ($null -eq $container) { return @{ ExitCode = 1; Output = "Error: No such object: $name" } }
                if ($format -like '*{{.Id}},*') {
                    $running = if ($container.running) { 'true' } else { 'false' }
                    $lines += "$($container.imageId),$($container.state),$running,$($container.imageId),$($container.imageName),$($container.restartPolicy)"
                } elseif ($format -like '*HostConfig.PortBindings*') {
                    $lines += (ConvertTo-Json -InputObject $container.hostBindings -Compress -Depth 8)
                } elseif ($format -like '*NetworkSettings.Ports*') {
                    $lines += (ConvertTo-Json -InputObject $container.publishedBindings -Compress -Depth 8)
                } elseif ($format -like '*NetworkSettings.Networks*') {
                    $lines += (ConvertTo-Json -InputObject @{ 'chen-stack-network' = @{ NetworkID = 'net-fake' } } -Compress -Depth 6)
                } elseif ($format -like '*Config.Labels*') {
                    $lines += (ConvertTo-Json -InputObject $container.labels -Compress -Depth 6)
                } elseif ($format -like '*Config.Env*') {
                    $lines += (ConvertTo-Json -InputObject @($container.env) -Compress -Depth 6)
                } elseif ($format -like '*Mounts*') {
                    $lines += (ConvertTo-Json -InputObject @($container.mounts) -Compress -Depth 8)
                } elseif ($format -like '*State.Health*') {
                    $lines += [string]$container.health
                } elseif ($format -like '*State.Running*') {
                    $lines += $(if ($container.running) { 'true' } else { 'false' })
                } elseif ($format -like '*json .State*') {
                    $stateObject = @{
                        Status    = [string]$container.state
                        Running   = [bool]$container.running
                        StartedAt = '2026-11-10T00:00:00Z'
                    }
                    $lines += (ConvertTo-Json -InputObject $stateObject -Compress -Depth 5)
                } elseif ($format -like '*State.Status*') {
                    $lines += "$($container.state)|2026-11-10T00:00:00Z|$($container.imageId)"
                } else {
                    return @{ ExitCode = 1; Output = "fake-docker: 未支持的 inspect 模板 $format" }
                }
            }
            return @{ ExitCode = 0; Output = ($lines -join "`n") }
        }
        'exec' {
            if ($Arguments.Count -lt 2) { return @{ ExitCode = 1; Output = 'fake-docker: exec 缺少容器' } }
            $name = $Arguments[1]
            $container = Get-FakeContainer -Scenario $Scenario -Name $name
            if ($null -eq $container) { return @{ ExitCode = 1; Output = "Error: No such container: $name" } }
            $rest = (@($Arguments) | Select-Object -Skip 2) -join ' '

            if ($rest -match 'actuator/health') {
                if ($container.internalHealth -eq 'UP') { return @{ ExitCode = 0; Output = '{"status":"UP"}' } }
                return @{ ExitCode = 1; Output = 'wget: server returned error: HTTP/1.1 503 Service Unavailable' }
            }
            if ($rest -match 'redis-cli') { return @{ ExitCode = 0; Output = 'PONG' } }
            if ($rest -match 'SELECT 1') { return @{ ExitCode = 0; Output = '1' } }
            if ($rest -match 'minio/health') { return @{ ExitCode = 0; Output = '' } }
            if ($rest -match 'rabbitmq-diagnostics') { return @{ ExitCode = 0; Output = 'Ping succeeded' } }
            if ($rest -match 'getent hosts') {
                $target = ($rest -split '\s+')[-1]
                if ($Scenario.dns.ContainsKey($name) -and $Scenario.dns[$name].ContainsKey($target)) {
                    return @{ ExitCode = 0; Output = "$($Scenario.dns[$name][$target]) $target $target" }
                }
                return @{ ExitCode = 1; Output = '' }
            }
            if ($rest -match 'grep -rhoE') {
                if ($container.backendUrl) { return @{ ExitCode = 0; Output = [string]$container.backendUrl } }
                return @{ ExitCode = 1; Output = '' }
            }
            if ($rest -match 'curl') { return @{ ExitCode = 0; Output = '' } }
            return @{ ExitCode = 0; Output = '' }
        }
        'logs' {
            if ($Arguments.Count -lt 2) { return @{ ExitCode = 1; Output = 'fake-docker: logs 缺少容器' } }
            $name = $Arguments[-1]
            $container = Get-FakeContainer -Scenario $Scenario -Name $name
            if ($null -eq $container) { return @{ ExitCode = 1; Output = "Error: No such container: $name" } }
            return @{ ExitCode = 0; Output = [string]$container.logs }
        }
        'restart' {
            $name = $Arguments[-1]
            $container = Get-FakeContainer -Scenario $Scenario -Name $name
            if ($null -eq $container) { return @{ ExitCode = 1; Output = "Error: No such container: $name" } }
            switch ($Scenario.repairEffect) {
                'fix' {
                    $container.publishedBindings = $container.hostBindings
                    $container.running = $true
                    $container.state = 'running'
                    Restore-FakeHttpEndpoint -Scenario $Scenario -Container $container
                }
                'image-change' {
                    $container.imageId = 'sha256:9999999999999999999999999999999999999999999999999999999999999999'
                    $container.running = $true
                    $container.state = 'running'
                    $container.publishedBindings = $container.hostBindings
                    Restore-FakeHttpEndpoint -Scenario $Scenario -Container $container
                }
                default { }
            }
            return @{ ExitCode = 0; Output = $name }
        }
        'start' {
            $name = $Arguments[-1]
            $container = Get-FakeContainer -Scenario $Scenario -Name $name
            if ($null -eq $container) { return @{ ExitCode = 1; Output = "Error: No such container: $name" } }
            $container.running = $true
            $container.state = 'running'
            return @{ ExitCode = 0; Output = $name }
        }
        default {
            return @{ ExitCode = 92; Output = "fake-docker: 未支持的命令 '$verb'" }
        }
    }
}

function Restore-FakeHttpEndpoint {
    <# 端口发布恢复后，让宿主机对应端口的 HTTP 也变为可达（模拟真实修复效果）。 #>
    param([hashtable]$Scenario, [hashtable]$Container)

    foreach ($key in $Container.hostBindings.Keys) {
        foreach ($binding in @($Container.hostBindings[$key])) {
            if ($binding -and $binding.HostPort) {
                $Scenario.http[[string]$binding.HostPort] = @{ status = 200; body = '{"status":"UP","groups":["liveness","readiness"]}' }
            }
        }
    }

}

function Invoke-FakeHttp {
    <# 按场景中的端口表返回 HTTP 结果；未登记的端口视为连接被拒绝。 #>
    param([hashtable]$Scenario, [string]$Uri)

    $port = 0
    if ($Uri -match ':(\d+)(/|$)') { $port = [int]$Matches[1] }
    $entry = $null
    if ($port -gt 0 -and $Scenario.http.ContainsKey([string]$port)) { $entry = $Scenario.http[[string]$port] }
    if ($null -eq $entry) {
        return @{ Ok = $false; StatusCode = 0; Body = ''; Error = '由于目标计算机积极拒绝，无法连接。' }
    }
    $status = [int]$entry.status
    return @{
        Ok         = ($status -ge 200 -and $status -lt 400)
        StatusCode = $status
        Body       = [string]$entry.body
        Error      = ''
    }
}


