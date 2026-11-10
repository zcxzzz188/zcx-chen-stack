#requires -Version 7.0
<#
.SYNOPSIS
    测试专用最小 HTTP 服务（只监听 IPv4 loopback），用于验证宿主机 HTTP 可达性。

.DESCRIPTION
    只用于 script/deploy/tests 的隔离验收。响应规则：
      * /actuator/health  -> {"status":"<HealthStatus>"}，HTTP 200
      * 其他路径          -> HTTP <RootStatus>，返回一段静态 HTML

    用法：
      pwsh -NoProfile -File fake-http.ps1 -Port 29106 -HealthStatus UP -RootStatus 200

#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][int]$Port,
    [ValidateSet('UP', 'DOWN')][string]$HealthStatus = 'UP',
    [int]$RootStatus = 200
)

$ErrorActionPreference = 'Stop'
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
$listener.Start()

try {
    while ($true) {
        $client = $listener.AcceptTcpClient()
        try {
            $stream = $client.GetStream()
            $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::ASCII, $false, 4096, $true)
            $requestLine = $reader.ReadLine()
            while ($true) {
                $line = $reader.ReadLine()
                if ($null -eq $line -or $line -eq '') { break }
            }

            $path = '/'
            if ($requestLine -match '^(?:GET|HEAD)\s+(\S+)') { $path = $Matches[1] }
            $isHealth = $path -like '/actuator/health*'

            if ($isHealth) {
                $body = "{`"status`":`"$HealthStatus`",`"groups`":[`"liveness`",`"readiness`"]}"
                $status = 200
                $contentType = 'application/vnd.spring-boot.actuator.v3+json'
            } else {
                $body = '<html><body>chen-stack test frontend</body></html>'
                $status = $RootStatus
                $contentType = 'text/html'
            }

            $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($body)
            $header = "HTTP/1.1 $status OK`r`nContent-Type: $contentType`r`nContent-Length: $($bodyBytes.Length)`r`nConnection: close`r`n`r`n"
            $headerBytes = [System.Text.Encoding]::ASCII.GetBytes($header)
            $stream.Write($headerBytes, 0, $headerBytes.Length)
            $stream.Write($bodyBytes, 0, $bodyBytes.Length)
            $stream.Flush()
            $reader.Dispose()
        } catch {
            # 测试客户端提前断开时忽略即可
        } finally {
            $client.Close()
        }
    }
} finally {
    $listener.Stop()
}

