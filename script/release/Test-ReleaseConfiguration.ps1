#requires -Version 7.0
[CmdletBinding()]
param(
    [hashtable]$Configuration,
    [string]$ArtifactPath = (Join-Path $PSScriptRoot '../../chen-stack-backend/target/zcx-chen-stack-backend-1.0-SNAPSHOT.jar'),
    [switch]$ExistingDeployment,
    [switch]$SelfTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Read-Boolean([hashtable]$Values, [string]$Name, [bool]$Default = $false) {
    $value = [string]$Values[$Name]
    if ([string]::IsNullOrWhiteSpace($value)) { return $Default }
    if ($value -ieq 'true') { return $true }
    if ($value -ieq 'false') { return $false }
    throw "Invalid Boolean setting: $Name"
}

function Read-BoundedFile([string]$Path, [int]$Limit) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw 'A required release file is missing.' }
    $stream = [IO.File]::OpenRead($Path)
    try {
        if ($stream.Length -lt 1 -or $stream.Length -gt $Limit) { throw 'A release input exceeds its size limit.' }
        $bytes = [byte[]]::new($stream.Length)
        $stream.ReadExactly($bytes)
        return ,$bytes
    } finally { $stream.Dispose() }
}

function Test-ReleaseFiles([hashtable]$Values, [bool]$Recovery) {
    $budgetDirectory = [string]$Values['CHEN_RELEASE_BUDGET_DIRECTORY']
    $budgetId = [string]$Values['CHEN_RAG_RELEASE_BUDGET_ID']
    if (-not [IO.Path]::IsPathFullyQualified($budgetDirectory) -or
        -not (Test-Path -LiteralPath $budgetDirectory -PathType Container) -or
        $budgetId -cnotmatch '^[A-Za-z0-9._-]{1,80}$' -or
        [string]$Values['CHEN_RAG_RELEASE_BUDGET_LEDGER_PATH'] -cne '/budget/provider-attempts.ledger') {
        throw 'Controlled release and E2E require a persistent budget directory, fixed budget ID and container path.'
    }
    $ledger = [Text.UTF8Encoding]::new($false,$true).GetString((Read-BoundedFile (Join-Path $budgetDirectory 'provider-attempts.ledger') 65536))
    if (-not $ledger.StartsWith("CHEN_RELEASE_HTTP_BUDGET_V1|$budgetId|64|20`n", [StringComparison]::Ordinal) -or
        -not $ledger.EndsWith("`n") -or $ledger.Contains("`r")) {
        throw 'Budget header, fixed 64/20 limits or complete record boundary differs; do not reset the ledger.'
    }
    $configuredChatLimit = [string]$Values['CHEN_RAG_RELEASE_MAX_CHAT_HTTP_ATTEMPTS']
    if ([string]::IsNullOrWhiteSpace($configuredChatLimit)) { $configuredChatLimit = '20' }
    if ($configuredChatLimit -cnotmatch '^(20|100)$') { throw 'The release Chat limit must be 20 or explicitly extended to 100.' }
    $effectiveChatLimit = 20
    $embeddingUsed = 0; $chatUsed = 0; $sequence = 0
    $lines = $ledger.Split("`n")
    for ($index = 1; $index -lt $lines.Length - 1; $index++) {
        $fields = $lines[$index].Split('|')
        if (($fields.Length -ne 4 -and $fields.Length -ne 8) -or
            $fields[0] -cnotmatch '^[1-9][0-9]{0,2}$' -or
            $fields[2] -cnotmatch '^[0-9]{1,2}$' -or $fields[3] -cnotmatch '^[0-9]{1,3}$') {
            throw 'Budget record structure is invalid.'
        }
        $sequence++
        if ($fields.Length -eq 8) {
            $auditTime = [DateTime]::MinValue
            if ($fields[1] -cne 'CHAT_LIMIT' -or $effectiveChatLimit -ne 20 -or
                $fields[4] -cne '20' -or $fields[5] -cne '100' -or
                $fields[7] -cnotmatch '^[A-Za-z0-9._-]{1,80}$' -or
                $fields[6] -cnotmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$' -or
                -not [DateTime]::TryParseExact($fields[6].Substring(0, 19), "yyyy-MM-dd'T'HH:mm:ss",
                    [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::AssumeUniversal, [ref]$auditTime)) {
                throw 'Budget Chat extension audit is invalid.'
            }
            $effectiveChatLimit = 100
        } elseif ($fields[1] -ceq 'EMBEDDING') { $embeddingUsed++ }
        elseif ($fields[1] -ceq 'CHAT') { $chatUsed++ }
        else { throw 'Budget request kind is invalid.' }
        if ([int]$fields[0] -ne $sequence -or [int]$fields[2] -ne $embeddingUsed -or
            [int]$fields[3] -ne $chatUsed -or $embeddingUsed -gt 64 -or $chatUsed -gt $effectiveChatLimit) {
            throw 'Budget sequence, cumulative counts or effective limits are invalid.'
        }
    }
    if ($effectiveChatLimit -ne [int]$configuredChatLimit) {
        throw 'Configured Chat limit differs from the audited budget; do not reset the ledger.'
    }
    $directory = [string]$Values['CHEN_RELEASE_CONTROL_DIRECTORY']
    if (-not [IO.Path]::IsPathFullyQualified($directory) -or -not (Test-Path -LiteralPath $directory -PathType Container)) {
        throw 'CHEN_RELEASE_CONTROL_DIRECTORY must name an existing absolute host directory.'
    }
    $control = (Get-Item -LiteralPath $directory).FullName
    $expectedPaths = @{
        MANIFEST_PATH = '/release/manifest.json'; ARTIFACT_PATH = '/app/app.jar'
    }
    if (-not $Recovery) { $expectedPaths['OPERATION_PATH'] = '/release/operation.json' }
    foreach ($name in $expectedPaths.Keys) {
        if ([string]$Values["CHEN_RAG_RELEASE_$name"] -cne $expectedPaths[$name]) {
            throw "CHEN_RAG_RELEASE_$name must match the container mount path."
        }
    }
    $raw = Read-BoundedFile (Join-Path $control 'manifest.json') 65536
    $utf8 = [Text.UTF8Encoding]::new($false, $true)
    try { $manifest = $utf8.GetString($raw) | ConvertFrom-Json -AsHashtable -ErrorAction Stop }
    catch { throw 'Manifest is not valid UTF-8 JSON.' }
    if ($manifest['operation'] -cnotin @('PUBLISH', 'UPDATE', 'RECOVER', 'REVOKE') -or
        $manifest['createCollection'] -isnot [bool] -or $manifest['initializeEmptyCatalog'] -isnot [bool]) {
        throw 'Manifest operation and collection/bootstrap flags are required.'
    }
    if ([string]$manifest['environment'] -cne [string]$Values['CHEN_RAG_RELEASE_ENVIRONMENT'] -or
        [string]$manifest['collection'] -cne [string]$Values['CHEN_RAG_QDRANT_COLLECTION']) {
        throw 'Manifest environment or collection does not match deployment configuration.'
    }
    $fresh = Read-Boolean $Values 'CHEN_RELEASE_NEW_COLLECTION_CONFIRMED'
    $creates = -not $Recovery -and $manifest['operation'] -cne 'REVOKE' -and $manifest['createCollection']
    if ($fresh -ne $creates) { throw 'Fresh-target confirmation must match controlled collection creation; read-only recovery and revocation use an existing target.' }
    if ($Recovery -and $manifest['operation'] -cnotin @('PUBLISH', 'UPDATE', 'RECOVER')) {
        throw 'Read-only restart recovery requires the completed PUBLISH, UPDATE or RECOVER receipt.'
    }
    if (-not $Recovery -and $manifest['operation'] -ceq 'REVOKE' -and
        ($manifest['createCollection'] -or $manifest['initializeEmptyCatalog'])) {
        throw 'REVOKE cannot initialize the Catalog or create a collection.'
    }
    if ($manifest['operation'] -ceq 'UPDATE' -and ($manifest['createCollection'] -or $manifest['initializeEmptyCatalog'])) {
        throw 'UPDATE must preserve the existing Catalog and collection.'
    }
    $manifestHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($raw)).ToLowerInvariant()
    $operationHash = $null
    if (-not $Recovery) {
        $operationBytes = Read-BoundedFile (Join-Path $control 'operation.json') 8192
        try { $record = $utf8.GetString($operationBytes) | ConvertFrom-Json -AsHashtable -ErrorAction Stop }
        catch { throw 'Operation record is not valid UTF-8 JSON.' }
        $fields = @('schemaVersion', 'operationId', 'operator', 'environment', 'manifestSha256', 'maxEmbeddingRequests', 'allowedOperation', 'confirmed')
        if ($record -isnot [System.Collections.IDictionary] -or $record.Count -ne $fields.Count -or
            @($fields | Where-Object { $record.Keys -cnotcontains $_ }).Count -ne 0 -or
            $record['schemaVersion'] -isnot [long] -and $record['schemaVersion'] -isnot [int] -or $record['schemaVersion'] -ne 1 -or
            [string]$record['operationId'] -cnotmatch '^[A-Za-z0-9._-]{1,80}$' -or
            [string]$record['manifestSha256'] -cne $manifestHash -or
            [string]$record['operator'] -cne [string]$Values['CHEN_RAG_RELEASE_OPERATOR'] -or
            [string]$record['environment'] -cne [string]$manifest['environment'] -or
            [string]$record['allowedOperation'] -cne [string]$manifest['operation'] -or
            $record['confirmed'] -isnot [bool] -or -not $record['confirmed'] -or
            $record['maxEmbeddingRequests'] -isnot [long] -and $record['maxEmbeddingRequests'] -isnot [int] -or
            $record['maxEmbeddingRequests'] -lt 0 -or $record['maxEmbeddingRequests'] -gt [int]::MaxValue -or
            ($manifest['operation'] -ceq 'REVOKE' -and $record['maxEmbeddingRequests'] -ne 0) -or
            ($manifest['operation'] -ceq 'UPDATE' -and $record['maxEmbeddingRequests'] -le 0)) {
            throw 'Operation record is unconfirmed or differs from the raw Manifest and deployment identities.'
        }
        $operationHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($operationBytes)).ToLowerInvariant()
    }
    if (-not (Test-Path -LiteralPath $ArtifactPath -PathType Leaf)) { throw 'The host artifact to be copied into /app/app.jar is missing.' }
    $artifactHash = (Get-FileHash -LiteralPath $ArtifactPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($artifactHash -cne [string]$manifest['artifactSha256']) { throw 'Host JAR SHA256 does not match the raw Manifest.' }
    $zip = [IO.Compression.ZipFile]::OpenRead($ArtifactPath)
    try {
        $entries = @($zip.Entries | Where-Object {
            $_.FullName -cin @('META-INF/chen-release-build.properties', 'BOOT-INF/classes/META-INF/chen-release-build.properties')
        })
        if ($entries.Count -ne 1) { throw 'JAR release metadata is missing or ambiguous.' }
        $reader = [IO.StreamReader]::new($entries[0].Open())
        try { $metadata = $reader.ReadToEnd() } finally { $reader.Dispose() }
        $match = [regex]::Match($metadata, '(?m)^gitCommit\s*=\s*([0-9a-f]{40})\s*$')
        if (-not $match.Success -or $match.Groups[1].Value -cne [string]$manifest['gitCommit']) {
            throw 'JAR embedded Git identity does not match the Manifest.'
        }
    } finally { $zip.Dispose() }
    [pscustomobject]@{
        Operation = $(if ($Recovery) { 'READ_ONLY_RESTART' } else { $manifest['operation'] })
        ManifestSha256 = $manifestHash
        ArtifactSha256 = $artifactHash
        OperationSha256 = $operationHash
        WriteEntryPoint = 'LOCAL_COMMAND_REQUIRED_NOT_VERIFIED_BY_THIS_SCRIPT'
        CatalogAndIndexState = 'NOT_VERIFIED_NO_NETWORK_CALLS'
    }
}

function Test-Configuration([hashtable]$Values, [bool]$Existing = $false) {
    $profiles = @(([string]$Values['SPRING_PROFILES_ACTIVE']).Split(',') | ForEach-Object { $_.Trim() })
    if ($profiles -cnotcontains 'rag') { throw 'SPRING_PROFILES_ACTIVE must explicitly include rag.' }
    $projectPattern = $(if ($Existing) { '^[a-z0-9][a-z0-9_-]{0,62}$' } else { '^chen-release-[a-z0-9][a-z0-9_-]{0,50}$' })
    if ([string]$Values['CHEN_RELEASE_PROJECT_NAME'] -cnotmatch $projectPattern) {
        throw 'CHEN_RELEASE_PROJECT_NAME is invalid for the selected candidate/existing deployment mode.'
    }
    $collection = [string]$Values['CHEN_RAG_QDRANT_COLLECTION']
    if ($collection -cnotmatch '^chen_[a-z0-9_]{1,90}$' -or $collection -ceq 'chen_platform_help_v1') {
        throw 'Set CHEN_RAG_QDRANT_COLLECTION to an explicit release target.'
    }
    $hostName = [string]$Values['CHEN_RAG_QDRANT_HOST']
    if ([string]::IsNullOrWhiteSpace($hostName) -or $hostName.Contains('/') -or $hostName.Contains(':')) {
        throw 'CHEN_RAG_QDRANT_HOST must be a hostname or IPv4 address, not a URL.'
    }
    $portText = [string]$Values['CHEN_RAG_QDRANT_GRPC_PORT']
    if ([string]::IsNullOrWhiteSpace($portText)) { $portText = '6334' }
    $port = 0
    if (-not [int]::TryParse($portText, [ref]$port) -or $port -lt 1 -or $port -gt 65535) {
        throw 'CHEN_RAG_QDRANT_GRPC_PORT must be between 1 and 65535.'
    }
    $tls = Read-Boolean $Values 'CHEN_RAG_QDRANT_TLS'
    $local = $hostName -ceq '127.0.0.1' -or $hostName -ceq 'localhost'
    if (-not $local -and -not $tls) { throw 'Non-loopback Qdrant requires TLS.' }
    $qdrantKey = [string]$Values['CHEN_RAG_QDRANT_API_KEY']
    $embeddingKey = [string]$Values['CHEN_RAG_EMBEDDING_API_KEY']
    $chatKey = [string]$Values['SPRING_AI_OPENAI_API_KEY']
    if ([string]::IsNullOrWhiteSpace($qdrantKey)) { throw 'A dedicated Qdrant API key is required.' }
    if (($embeddingKey -and $qdrantKey -ceq $embeddingKey) -or ($chatKey -and $qdrantKey -ceq $chatKey) -or
        ($embeddingKey -and $chatKey -and $embeddingKey -ceq $chatKey)) {
        throw 'Chat, Embedding and Qdrant keys must be independent.'
    }
    $ragEnabled = Read-Boolean $Values 'CHEN_RAG_ENABLED'
    $releaseEnabled = Read-Boolean $Values 'CHEN_RAG_RELEASE_ENABLED'
    $recoveryEnabled = Read-Boolean $Values 'CHEN_RAG_RELEASE_RECOVERY_ENABLED'
    if ($releaseEnabled -and $recoveryEnabled) { throw 'Release and read-only recovery cannot be enabled together.' }
    if (($releaseEnabled -or $recoveryEnabled) -and -not $ragEnabled) {
        throw 'Enabled release or recovery also requires CHEN_RAG_ENABLED=true.'
    }
    if ($ragEnabled -and [string]::IsNullOrWhiteSpace($embeddingKey)) {
        throw 'Enabled RAG requires its independent Embedding API key.'
    }
    $files = $null
    if ($releaseEnabled -or $recoveryEnabled) {
        foreach ($name in @('MANIFEST_PATH', 'OPERATOR', 'ENVIRONMENT', 'ARTIFACT_PATH')) {
            if ([string]::IsNullOrWhiteSpace([string]$Values["CHEN_RAG_RELEASE_$name"])) {
                throw "Enabled release or recovery requires CHEN_RAG_RELEASE_$name."
            }
        }
        $operator = [string]$Values['CHEN_RAG_RELEASE_OPERATOR']
        if ($operator.Length -gt 128 -or $operator -cne $operator.Trim() -or $operator -match '[\x00-\x1f\x7f]' -or
            [string]$Values['CHEN_RAG_RELEASE_ENVIRONMENT'] -cnotmatch '^[A-Za-z0-9._-]{1,80}$') {
            throw 'Release operator or environment is invalid.'
        }
        $files = Test-ReleaseFiles $Values $recoveryEnabled
    }
    [pscustomobject]@{
        Result = 'CONFIGURATION_ONLY_PASS'
        RagEnabled = $ragEnabled
        ReleaseEnabled = $releaseEnabled
        RecoveryEnabled = $recoveryEnabled
        Deployment = $(if ($Existing) { 'EXISTING_PROJECT_CONFIGURATION_ONLY' } else { 'DEDICATED_CANDIDATE_PROJECT' })
        QdrantConnection = $(if ($local) { 'LOOPBACK_WITH_KEY' } else { 'TLS_WITH_KEY' })
        TargetCollection = $collection
        TargetCollectionExistence = 'NOT_VERIFIED_NO_NETWORK_CALLS'
        ReleaseFiles = $files
    }
}

if ($SelfTest) {
    $baseline = @{
        SPRING_PROFILES_ACTIVE = 'docker,rag'; CHEN_RELEASE_PROJECT_NAME = 'chen-release-selftest'
        CHEN_RAG_QDRANT_COLLECTION = 'chen_release_selftest'; CHEN_RELEASE_NEW_COLLECTION_CONFIRMED = 'false'
        CHEN_RAG_QDRANT_HOST = '127.0.0.1'; CHEN_RAG_QDRANT_API_KEY = 'synthetic-qdrant'
        CHEN_RAG_EMBEDDING_API_KEY = 'synthetic-embedding'; SPRING_AI_OPENAI_API_KEY = 'synthetic-chat'
    }
    $result = Test-Configuration $baseline
    if ($result.RagEnabled -or $result.ReleaseEnabled -or $result.RecoveryEnabled) { throw 'Disabled defaults regression.' }
    $cases = @(
        @{ SPRING_PROFILES_ACTIVE = 'docker' }, @{ CHEN_RAG_QDRANT_COLLECTION = '' },
        @{ CHEN_RAG_QDRANT_COLLECTION = 'chen_platform_help_v1' },
        @{ CHEN_RAG_QDRANT_HOST = 'qdrant.example.invalid'; CHEN_RAG_QDRANT_TLS = 'false' },
        @{ CHEN_RAG_QDRANT_API_KEY = '' }, @{ CHEN_RAG_QDRANT_API_KEY = 'synthetic-chat' },
        @{ CHEN_RAG_EMBEDDING_API_KEY = 'synthetic-chat' }, @{ CHEN_RAG_QDRANT_GRPC_PORT = '65536' },
        @{ CHEN_RAG_ENABLED = 'maybe' }, @{ CHEN_RELEASE_PROJECT_NAME = 'chen-stack' },
        @{ CHEN_RAG_RELEASE_ENABLED = 'true' }, @{ CHEN_RAG_RELEASE_RECOVERY_ENABLED = 'true' },
        @{ CHEN_RAG_RELEASE_RECOVERY_ENABLED = 'invalid' },
        @{ CHEN_RAG_ENABLED = 'true'; CHEN_RAG_EMBEDDING_API_KEY = '' }
    )
    foreach ($case in $cases) {
        $values = $baseline.Clone()
        foreach ($name in $case.Keys) { $values[$name] = $case[$name] }
        $rejected = $false
        try { Test-Configuration $values | Out-Null } catch { $rejected = $true }
        if (-not $rejected) { throw 'An unsafe synthetic configuration was accepted.' }
    }
    $remote = $baseline.Clone()
    $remote['CHEN_RAG_QDRANT_HOST'] = 'qdrant.example.invalid'
    $remote['CHEN_RAG_QDRANT_TLS'] = 'true'
    Test-Configuration $remote | Out-Null
    $enabledRag = $baseline.Clone()
    $enabledRag['CHEN_RAG_ENABLED'] = 'true'
    Test-Configuration $enabledRag | Out-Null
    $selfTestRoot = Join-Path ([IO.Path]::GetTempPath()) ('chen-release-config-' + [Guid]::NewGuid().ToString('N'))
    $control = New-Item -ItemType Directory -Path (Join-Path $selfTestRoot 'control')
    $budget = New-Item -ItemType Directory -Path (Join-Path $selfTestRoot 'budget')
    [IO.File]::WriteAllText((Join-Path $budget.FullName 'provider-attempts.ledger'), "CHEN_RELEASE_HTTP_BUDGET_V1|synthetic|64|20`n", [Text.UTF8Encoding]::new($false))
    try {
        $ArtifactPath = Join-Path $selfTestRoot 'synthetic.jar'
        $zip = [IO.Compression.ZipFile]::Open($ArtifactPath, [IO.Compression.ZipArchiveMode]::Create)
        try {
            $writer = [IO.StreamWriter]::new($zip.CreateEntry('META-INF/chen-release-build.properties').Open())
            try { $writer.Write('gitCommit=' + ('a' * 40)) } finally { $writer.Dispose() }
        } finally { $zip.Dispose() }
        $manifest = @{ operation = 'PUBLISH'; createCollection = $true; initializeEmptyCatalog = $true
            environment = 'synthetic'; collection = 'chen_release_selftest'; gitCommit = 'a' * 40
            artifactSha256 = (Get-FileHash -LiteralPath $ArtifactPath).Hash.ToLowerInvariant() }
        $manifestPath = Join-Path $control.FullName 'manifest.json'
        function Write-SyntheticInputs {
            $manifest | ConvertTo-Json | Set-Content -LiteralPath $manifestPath -Encoding utf8NoBOM
            $record = @{ schemaVersion = 1; operationId = 'synthetic-operation'; operator = 'synthetic-operator'; environment = 'synthetic'
                manifestSha256 = (Get-FileHash -LiteralPath $manifestPath).Hash.ToLowerInvariant()
                maxEmbeddingRequests = $(if ($manifest['operation'] -ceq 'UPDATE') { 1 } else { 0 }); allowedOperation = $manifest['operation']; confirmed = $true }
            $record | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $control.FullName 'operation.json') -Encoding utf8NoBOM
        }
        Write-SyntheticInputs
        $enabled = $baseline.Clone()
        $enabled['CHEN_RAG_ENABLED'] = 'true'; $enabled['CHEN_RAG_RELEASE_ENABLED'] = 'true'
        $enabled['CHEN_RELEASE_NEW_COLLECTION_CONFIRMED'] = 'true'
        $enabled['CHEN_RELEASE_CONTROL_DIRECTORY'] = $control.FullName
        $enabled['CHEN_RELEASE_BUDGET_DIRECTORY'] = $budget.FullName
        $enabled['CHEN_RAG_RELEASE_BUDGET_ID'] = 'synthetic'
        $enabled['CHEN_RAG_RELEASE_BUDGET_LEDGER_PATH'] = '/budget/provider-attempts.ledger'
        $enabled['CHEN_RAG_RELEASE_OPERATOR'] = 'synthetic-operator'; $enabled['CHEN_RAG_RELEASE_ENVIRONMENT'] = 'synthetic'
        $enabled['CHEN_RAG_RELEASE_MANIFEST_PATH'] = '/release/manifest.json'; $enabled['CHEN_RAG_RELEASE_OPERATION_PATH'] = '/release/operation.json'
        $enabled['CHEN_RAG_RELEASE_ARTIFACT_PATH'] = '/app/app.jar'
        Test-Configuration $enabled | Out-Null
        $ledgerPath = Join-Path $budget.FullName 'provider-attempts.ledger'
        $ledgerHeader = "CHEN_RELEASE_HTTP_BUDGET_V1|synthetic|64|20`n"
        $legacyRecords = [Text.StringBuilder]::new($ledgerHeader)
        for ($count = 1; $count -le 6; $count++) { [void]$legacyRecords.Append("$count|EMBEDDING|$count|0`n") }
        for ($count = 1; $count -le 20; $count++) { [void]$legacyRecords.Append("$($count + 6)|CHAT|6|$count`n") }
        $legacyLedger = $legacyRecords.ToString()
        $auditRecord = "27|CHAT_LIMIT|6|20|20|100|2026-10-25T00:00:00.123456789Z|synthetic-operator`n"
        $expandedLedger = $legacyLedger + $auditRecord + "28|CHAT|6|21`n"
        [IO.File]::WriteAllText($ledgerPath, $legacyLedger, [Text.UTF8Encoding]::new($false))
        Test-Configuration $enabled | Out-Null
        $enabled['CHEN_RAG_RELEASE_MAX_CHAT_HTTP_ATTEMPTS'] = '20'
        Test-Configuration $enabled | Out-Null
        $enabled['CHEN_RAG_RELEASE_MAX_CHAT_HTTP_ATTEMPTS'] = '100'
        [IO.File]::WriteAllText($ledgerPath, $expandedLedger, [Text.UTF8Encoding]::new($false))
        Test-Configuration $enabled | Out-Null
        $overChatLimit = [Text.StringBuilder]::new($legacyLedger + $auditRecord)
        for ($count = 21; $count -le 101; $count++) { [void]$overChatLimit.Append("$($count + 7)|CHAT|6|$count`n") }
        $overEmbeddingLimit = [Text.StringBuilder]::new($legacyLedger + $auditRecord)
        for ($count = 7; $count -le 65; $count++) { [void]$overEmbeddingLimit.Append("$($count + 21)|EMBEDDING|$count|20`n") }
        $budgetCases = @(
            @{ Limit = '100'; Ledger = $legacyLedger },
            @{ Limit = '100'; Ledger = $expandedLedger.Replace('|64|20', '|64|100') },
            @{ Limit = '20'; Ledger = $expandedLedger },
            @{ Limit = '101'; Ledger = $expandedLedger },
            @{ Limit = '100'; Ledger = $expandedLedger.Replace('27|CHAT_LIMIT', '28|CHAT_LIMIT') },
            @{ Limit = '100'; Ledger = $expandedLedger.Replace('|20|100|', '|19|100|') },
            @{ Limit = '100'; Ledger = $expandedLedger.Replace('|20|100|', '|20|99|') },
            @{ Limit = '100'; Ledger = $expandedLedger.Replace('synthetic-operator', '') },
            @{ Limit = '100'; Ledger = $expandedLedger.Replace('2026-10-25', '2026-02-30') },
            @{ Limit = '100'; Ledger = $expandedLedger.Replace('CHAT_LIMIT|6|20', 'CHAT_LIMIT|7|20') },
            @{ Limit = '100'; Ledger = $expandedLedger.Replace('CHAT_LIMIT|6|20', 'CHAT_LIMIT|6|21') },
            @{ Limit = '100'; Ledger = $expandedLedger.Replace('28|CHAT|6|21', '28|CHAT|6|22') },
            @{ Limit = '100'; Ledger = $legacyLedger + $auditRecord + $auditRecord.Replace('27|CHAT_LIMIT', '28|CHAT_LIMIT') },
            @{ Limit = '100'; Ledger = $legacyLedger + "27|CHAT|6|21`n" + $auditRecord.Replace('27|CHAT_LIMIT|6|20', '28|CHAT_LIMIT|6|21') },
            @{ Limit = '100'; Ledger = $overChatLimit.ToString() },
            @{ Limit = '100'; Ledger = $overEmbeddingLimit.ToString() }
        )
        foreach ($case in $budgetCases) {
            $enabled['CHEN_RAG_RELEASE_MAX_CHAT_HTTP_ATTEMPTS'] = $case.Limit
            [IO.File]::WriteAllText($ledgerPath, $case.Ledger, [Text.UTF8Encoding]::new($false))
            $rejected = $false
            try { Test-Configuration $enabled | Out-Null } catch { $rejected = $true }
            if (-not $rejected) { throw 'An unaudited, corrupt or over-budget Chat extension was accepted.' }
        }
        [IO.File]::WriteAllText($ledgerPath, $ledgerHeader, [Text.UTF8Encoding]::new($false))
        $enabled.Remove('CHEN_RAG_RELEASE_MAX_CHAT_HTTP_ATTEMPTS')
        $fileCases = @(
            @{ CHEN_RAG_ENABLED = 'false' }, @{ CHEN_RAG_RELEASE_RECOVERY_ENABLED = 'true' },
            @{ CHEN_RELEASE_BUDGET_DIRECTORY = (Join-Path $selfTestRoot 'missing-budget') },
            @{ CHEN_RAG_RELEASE_BUDGET_ID = 'changed' }, @{ CHEN_RAG_RELEASE_BUDGET_LEDGER_PATH = '/budget/wrong' },
            @{ CHEN_RELEASE_NEW_COLLECTION_CONFIRMED = 'false' }, @{ CHEN_RAG_RELEASE_OPERATOR = 'wrong-operator' },
            @{ CHEN_RAG_RELEASE_OPERATION_PATH = '/release/wrong-operation.json' },
            @{ CHEN_RAG_RELEASE_ENVIRONMENT = 'wrong' }, @{ CHEN_RELEASE_CONTROL_DIRECTORY = (Join-Path $selfTestRoot 'missing') }
        )
        foreach ($case in $fileCases) {
            $values = $enabled.Clone(); foreach ($name in $case.Keys) { $values[$name] = $case[$name] }
            $rejected = $false
            try { Test-Configuration $values | Out-Null } catch { $rejected = $true }
            if (-not $rejected) { throw 'An invalid release path or mode was accepted.' }
        }
        $operationPath = Join-Path $control.FullName 'operation.json'
        $recordCases = @(
            @{ confirmed = $false }, @{ confirmed = 'true' }, @{ schemaVersion = 2 }, @{ schemaVersion = '1' },
            @{ operationId = 'a' * 81 }, @{ operationId = '非ASCII' }, @{ manifestSha256 = '0' * 64 },
            @{ operator = 'wrong-operator' }, @{ environment = 'wrong' }, @{ allowedOperation = 'RECOVER' },
            @{ maxEmbeddingRequests = -1 }, @{ maxEmbeddingRequests = '1' }, @{ unexpected = 'denied' }
        )
        foreach ($case in $recordCases) {
            Write-SyntheticInputs
            $record = Get-Content -LiteralPath $operationPath -Raw | ConvertFrom-Json -AsHashtable
            foreach ($name in $case.Keys) { $record[$name] = $case[$name] }
            $record | ConvertTo-Json | Set-Content -LiteralPath $operationPath -Encoding utf8NoBOM
            $rejected = $false
            try { Test-Configuration $enabled | Out-Null } catch { $rejected = $true }
            if (-not $rejected) { throw 'An invalid or unconfirmed operation record was accepted.' }
        }
        Write-SyntheticInputs
        Remove-Item -LiteralPath $operationPath
        $rejected = $false
        try { Test-Configuration $enabled | Out-Null } catch { $rejected = $true }
        if (-not $rejected) { throw 'Write mode accepted a missing operation record.' }
        $enabled['CHEN_RAG_RELEASE_ENABLED'] = 'false'; $enabled['CHEN_RAG_RELEASE_RECOVERY_ENABLED'] = 'true'
        $enabled['CHEN_RELEASE_NEW_COLLECTION_CONFIRMED'] = 'false'
        $enabled.Remove('CHEN_RAG_RELEASE_OPERATION_PATH')
        Test-Configuration $enabled | Out-Null
        if (Test-Path -LiteralPath $operationPath) { throw 'Read-only precheck unexpectedly created an operation record.' }
        $enabled['CHEN_RAG_RELEASE_RECOVERY_ENABLED'] = 'false'; $enabled['CHEN_RAG_RELEASE_ENABLED'] = 'true'
        $enabled['CHEN_RAG_RELEASE_OPERATION_PATH'] = '/release/operation.json'
        foreach ($operation in @('UPDATE', 'RECOVER', 'REVOKE')) {
            $manifest['operation'] = $operation; $manifest['createCollection'] = $false; $manifest['initializeEmptyCatalog'] = $false
            Write-SyntheticInputs
            Test-Configuration $enabled | Out-Null
        }
        $record = Get-Content -LiteralPath $operationPath -Raw | ConvertFrom-Json -AsHashtable
        $record['maxEmbeddingRequests'] = 1
        $record | ConvertTo-Json | Set-Content -LiteralPath $operationPath -Encoding utf8NoBOM
        $rejected = $false
        try { Test-Configuration $enabled | Out-Null } catch { $rejected = $true }
        if (-not $rejected) { throw 'REVOKE accepted a nonzero embedding budget.' }
        $enabled['CHEN_RAG_RELEASE_ENABLED'] = 'false'; $enabled['CHEN_RAG_RELEASE_RECOVERY_ENABLED'] = 'true'
        $rejected = $false
        try { Test-Configuration $enabled | Out-Null } catch { $rejected = $true }
        if (-not $rejected) { throw 'REVOKE receipt was accepted for read-only recovery.' }
        $existing = $baseline.Clone(); $existing['CHEN_RELEASE_PROJECT_NAME'] = 'chen-stack'
        Test-Configuration $existing $true | Out-Null
        [pscustomobject]@{ Result = 'SELF_TEST_PASS'; Checks = $cases.Count + $fileCases.Count + $recordCases.Count + $budgetCases.Count + 16; ExternalCalls = 0 }
    } finally {
        $resolved = [IO.Path]::GetFullPath($selfTestRoot)
        $temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
        if (-not $resolved.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase) -or
            [IO.Path]::GetFileName($resolved) -cnotmatch '^chen-release-config-[0-9a-f]{32}$') { throw 'Unsafe self-test cleanup target.' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
    return
}

if (-not $PSBoundParameters.ContainsKey('Configuration')) {
    $Configuration = @{}
    foreach ($name in @('SPRING_PROFILES_ACTIVE', 'CHEN_RELEASE_PROJECT_NAME', 'CHEN_RELEASE_NEW_COLLECTION_CONFIRMED',
        'CHEN_RAG_ENABLED', 'CHEN_RAG_RELEASE_ENABLED', 'CHEN_RAG_RELEASE_RECOVERY_ENABLED', 'CHEN_RAG_QDRANT_HOST', 'CHEN_RAG_QDRANT_GRPC_PORT',
        'CHEN_RAG_QDRANT_TLS', 'CHEN_RAG_QDRANT_API_KEY', 'CHEN_RAG_QDRANT_COLLECTION',
        'CHEN_RAG_EMBEDDING_API_KEY', 'SPRING_AI_OPENAI_API_KEY', 'CHEN_RAG_RELEASE_MANIFEST_PATH',
        'CHEN_RAG_RELEASE_OPERATION_PATH', 'CHEN_RAG_RELEASE_OPERATOR',
        'CHEN_RAG_RELEASE_ENVIRONMENT', 'CHEN_RAG_RELEASE_ARTIFACT_PATH', 'CHEN_RELEASE_CONTROL_DIRECTORY',
        'CHEN_RELEASE_BUDGET_DIRECTORY', 'CHEN_RAG_RELEASE_BUDGET_ID', 'CHEN_RAG_RELEASE_BUDGET_LEDGER_PATH',
        'CHEN_RAG_RELEASE_MAX_CHAT_HTTP_ATTEMPTS')) {
        $Configuration[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
}
Test-Configuration $Configuration $ExistingDeployment.IsPresent
