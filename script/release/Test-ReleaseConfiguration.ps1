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

function Read-Base64Url([string]$Value, [int]$Limit) {
    if ($Value -cnotmatch '^[A-Za-z0-9_-]+$' -or $Value.Length -gt $Limit * 2) { throw 'Invalid approval encoding.' }
    try { $bytes = [Convert]::FromBase64String($Value.Replace('-', '+').Replace('_', '/') + ('=' * ((4 - $Value.Length % 4) % 4))) }
    catch { throw 'Invalid approval encoding.' }
    if ($bytes.Length -gt $Limit -or [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_') -cne $Value) {
        throw 'Invalid approval encoding.'
    }
    return ,$bytes
}

function Test-ReleaseFiles([hashtable]$Values, [bool]$Recovery) {
    foreach ($name in @('CONTROL', 'TRUST')) {
        $directory = [string]$Values["CHEN_RELEASE_${name}_DIRECTORY"]
        if (-not [IO.Path]::IsPathFullyQualified($directory) -or -not (Test-Path -LiteralPath $directory -PathType Container)) {
            throw "CHEN_RELEASE_${name}_DIRECTORY must name an existing absolute host directory."
        }
    }
    $control = (Get-Item -LiteralPath $Values['CHEN_RELEASE_CONTROL_DIRECTORY']).FullName.TrimEnd([IO.Path]::DirectorySeparatorChar)
    $trust = (Get-Item -LiteralPath $Values['CHEN_RELEASE_TRUST_DIRECTORY']).FullName.TrimEnd([IO.Path]::DirectorySeparatorChar)
    $separator = [IO.Path]::DirectorySeparatorChar
    if ($control -ieq $trust -or $control.StartsWith($trust + $separator, [StringComparison]::OrdinalIgnoreCase) -or
        $trust.StartsWith($control + $separator, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Control and independently managed trust directories must be separate, without nesting.'
    }
    $expectedPaths = @{
        MANIFEST_PATH = '/release/manifest.json'; APPROVAL_PATH = '/release/approval.json'
        PUBLIC_KEY_PATH = '/trust/release-public.pem'; ARTIFACT_PATH = '/app/app.jar'
    }
    foreach ($name in $expectedPaths.Keys) {
        if ([string]$Values["CHEN_RAG_RELEASE_$name"] -cne $expectedPaths[$name]) {
            throw "CHEN_RAG_RELEASE_$name must match the container mount path."
        }
    }
    $raw = Read-BoundedFile (Join-Path $control 'manifest.json') 65536
    $utf8 = [Text.UTF8Encoding]::new($false, $true)
    try { $manifest = $utf8.GetString($raw) | ConvertFrom-Json -AsHashtable -ErrorAction Stop }
    catch { throw 'Manifest is not valid UTF-8 JSON.' }
    if ($manifest['operation'] -cnotin @('PUBLISH', 'RECOVER', 'REVOKE') -or
        $manifest['createCollection'] -isnot [bool] -or $manifest['initializeEmptyCatalog'] -isnot [bool]) {
        throw 'Manifest operation and collection/bootstrap flags are required.'
    }
    if ([string]$manifest['environment'] -cne [string]$Values['CHEN_RAG_RELEASE_ENVIRONMENT'] -or
        [string]$manifest['collection'] -cne [string]$Values['CHEN_RAG_QDRANT_COLLECTION']) {
        throw 'Manifest environment or collection does not match deployment configuration.'
    }
    $fresh = Read-Boolean $Values 'CHEN_RELEASE_NEW_COLLECTION_CONFIRMED'
    $creates = -not $Recovery -and $manifest['operation'] -cne 'REVOKE' -and $manifest['createCollection']
    if ($fresh -ne $creates) { throw 'Fresh-target confirmation must match a signed collection creation; read-only recovery and revocation use an existing target.' }
    if ($Recovery -and $manifest['operation'] -cnotin @('PUBLISH', 'RECOVER')) {
        throw 'Read-only restart recovery requires the completed PUBLISH or RECOVER receipt.'
    }
    if (-not $Recovery -and $manifest['operation'] -ceq 'REVOKE' -and
        ($manifest['createCollection'] -or $manifest['initializeEmptyCatalog'])) {
        throw 'REVOKE cannot initialize the Catalog or create a collection.'
    }
    $approval = Read-BoundedFile (Join-Path $control 'approval.json') 16384
    try { $envelope = $utf8.GetString($approval) | ConvertFrom-Json -AsHashtable -ErrorAction Stop }
    catch { throw 'Approval envelope is not valid UTF-8 JSON.' }
    $signature = Read-Base64Url ([string]$envelope['signature']) 64
    if ($signature.Length -ne 64) { throw 'Ed25519 signatures must be 64 bytes.' }
    $payload = Read-Base64Url ([string]$envelope['payload']) 8192
    try { $grant = $utf8.GetString($payload) | ConvertFrom-Json -AsHashtable -ErrorAction Stop }
    catch { throw 'Approval payload is not valid UTF-8 JSON.' }
    $manifestHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($raw)).ToLowerInvariant()
    $expiresAt = [DateTimeOffset]::MinValue
    $budget = 0
    if ([string]$grant['manifestSha256'] -cne $manifestHash -or
        [string]$grant['operator'] -cne [string]$Values['CHEN_RAG_RELEASE_OPERATOR'] -or
        [string]::IsNullOrWhiteSpace([string]$grant['approver']) -or $grant['approver'] -ceq $grant['operator'] -or
        [string]$grant['environment'] -cne [string]$manifest['environment'] -or
        [string]$grant['allowedOperation'] -cne [string]$manifest['operation'] -or
        -not [DateTimeOffset]::TryParse([string]$grant['expiresAt'], [ref]$expiresAt) -or
        -not [int]::TryParse([string]$grant['maxEmbeddingRequests'], [ref]$budget) -or $budget -lt 0 -or
        ($manifest['operation'] -ceq 'REVOKE' -and $budget -ne 0)) {
        throw 'Approval fields do not match the raw Manifest or deployment identities.'
    }
    if (-not $Recovery -and $expiresAt -le [DateTimeOffset]::UtcNow) { throw 'Approval expired for a new controlled operation.' }
    $publicKey = Read-BoundedFile (Join-Path $trust 'release-public.pem') 8192
    $pem = [Text.Encoding]::ASCII.GetString($publicKey)
    if ($pem -cnotmatch '\A-----BEGIN PUBLIC KEY-----\s+([A-Za-z0-9+/=\s]+)-----END PUBLIC KEY-----\s*\z') {
        throw 'Trust input must be an Ed25519 SPKI PUBLIC KEY PEM.'
    }
    try { $spki = [Convert]::FromBase64String(($Matches[1] -replace '\s', '')) }
    catch { throw 'Trust public key has invalid Base64.' }
    if ($spki.Length -ne 44 -or [Convert]::ToHexString($spki[0..11]) -cne '302A300506032B6570032100') {
        throw 'Trust public key must use Ed25519 SPKI format.'
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
        SignatureAuthority = 'NOT_VERIFIED_BACKEND_MUST_VERIFY'
        HostAclAndSignerIsolation = 'MANUAL_PENDING'
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
        foreach ($name in @('MANIFEST_PATH', 'APPROVAL_PATH', 'PUBLIC_KEY_PATH', 'OPERATOR', 'ENVIRONMENT', 'ARTIFACT_PATH')) {
            if ([string]::IsNullOrWhiteSpace([string]$Values["CHEN_RAG_RELEASE_$name"])) {
                throw "Enabled release or recovery requires CHEN_RAG_RELEASE_$name."
            }
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
    if ($result.RagEnabled -or $result.ReleaseEnabled) { throw 'Disabled defaults regression.' }
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
    $trust = New-Item -ItemType Directory -Path (Join-Path $selfTestRoot 'trust')
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
            $grant = @{ approver = 'synthetic-reviewer'; operator = 'synthetic-operator'; environment = 'synthetic'
                manifestSha256 = (Get-FileHash -LiteralPath $manifestPath).Hash.ToLowerInvariant()
                expiresAt = [DateTimeOffset]::UtcNow.AddHours(1).ToString('o'); maxEmbeddingRequests = 0
                allowedOperation = $manifest['operation'] }
            $payload = [Text.Encoding]::UTF8.GetBytes(($grant | ConvertTo-Json -Compress))
            @{ payload = [Convert]::ToBase64String($payload).TrimEnd('=').Replace('+', '-').Replace('/', '_')
                signature = [Convert]::ToBase64String([byte[]]::new(64)).TrimEnd('=').Replace('+', '-').Replace('/', '_') } |
                ConvertTo-Json | Set-Content -LiteralPath (Join-Path $control.FullName 'approval.json') -Encoding utf8NoBOM
        }
        Write-SyntheticInputs
        # Synthetic public bytes only; the backend must still verify a real signature and trust anchor.
        $spki = [Convert]::FromHexString('302A300506032B6570032100' + ('00' * 32))
        Set-Content -LiteralPath (Join-Path $trust.FullName 'release-public.pem') -Encoding ascii -Value (
            "-----BEGIN PUBLIC KEY-----`n" + [Convert]::ToBase64String($spki) + "`n-----END PUBLIC KEY-----")
        $enabled = $baseline.Clone()
        $enabled['CHEN_RAG_ENABLED'] = 'true'; $enabled['CHEN_RAG_RELEASE_ENABLED'] = 'true'
        $enabled['CHEN_RELEASE_NEW_COLLECTION_CONFIRMED'] = 'true'
        $enabled['CHEN_RELEASE_CONTROL_DIRECTORY'] = $control.FullName; $enabled['CHEN_RELEASE_TRUST_DIRECTORY'] = $trust.FullName
        $enabled['CHEN_RAG_RELEASE_OPERATOR'] = 'synthetic-operator'; $enabled['CHEN_RAG_RELEASE_ENVIRONMENT'] = 'synthetic'
        $enabled['CHEN_RAG_RELEASE_MANIFEST_PATH'] = '/release/manifest.json'; $enabled['CHEN_RAG_RELEASE_APPROVAL_PATH'] = '/release/approval.json'
        $enabled['CHEN_RAG_RELEASE_PUBLIC_KEY_PATH'] = '/trust/release-public.pem'; $enabled['CHEN_RAG_RELEASE_ARTIFACT_PATH'] = '/app/app.jar'
        Test-Configuration $enabled | Out-Null
        $fileCases = @(
            @{ CHEN_RAG_ENABLED = 'false' }, @{ CHEN_RAG_RELEASE_RECOVERY_ENABLED = 'true' },
            @{ CHEN_RELEASE_NEW_COLLECTION_CONFIRMED = 'false' }, @{ CHEN_RELEASE_TRUST_DIRECTORY = $control.FullName },
            @{ CHEN_RAG_RELEASE_PUBLIC_KEY_PATH = '/release/release-public.pem' },
            @{ CHEN_RAG_RELEASE_ENVIRONMENT = 'wrong' }, @{ CHEN_RELEASE_CONTROL_DIRECTORY = (Join-Path $selfTestRoot 'missing') }
        )
        foreach ($case in $fileCases) {
            $values = $enabled.Clone(); foreach ($name in $case.Keys) { $values[$name] = $case[$name] }
            $rejected = $false
            try { Test-Configuration $values | Out-Null } catch { $rejected = $true }
            if (-not $rejected) { throw 'An invalid release path or mode was accepted.' }
        }
        $enabled['CHEN_RAG_RELEASE_ENABLED'] = 'false'; $enabled['CHEN_RAG_RELEASE_RECOVERY_ENABLED'] = 'true'
        $enabled['CHEN_RELEASE_NEW_COLLECTION_CONFIRMED'] = 'false'
        Test-Configuration $enabled | Out-Null
        $enabled['CHEN_RAG_RELEASE_RECOVERY_ENABLED'] = 'false'; $enabled['CHEN_RAG_RELEASE_ENABLED'] = 'true'
        foreach ($operation in @('RECOVER', 'REVOKE')) {
            $manifest['operation'] = $operation; $manifest['createCollection'] = $false; $manifest['initializeEmptyCatalog'] = $false
            Write-SyntheticInputs
            Test-Configuration $enabled | Out-Null
        }
        $enabled['CHEN_RAG_RELEASE_ENABLED'] = 'false'; $enabled['CHEN_RAG_RELEASE_RECOVERY_ENABLED'] = 'true'
        $rejected = $false
        try { Test-Configuration $enabled | Out-Null } catch { $rejected = $true }
        if (-not $rejected) { throw 'REVOKE receipt was accepted for read-only recovery.' }
        $existing = $baseline.Clone(); $existing['CHEN_RELEASE_PROJECT_NAME'] = 'chen-stack'
        Test-Configuration $existing $true | Out-Null
        [pscustomobject]@{ Result = 'SELF_TEST_PASS'; Checks = $cases.Count + $fileCases.Count + 9; ExternalCalls = 0 }
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
        'CHEN_RAG_RELEASE_APPROVAL_PATH', 'CHEN_RAG_RELEASE_PUBLIC_KEY_PATH', 'CHEN_RAG_RELEASE_OPERATOR',
        'CHEN_RAG_RELEASE_ENVIRONMENT', 'CHEN_RAG_RELEASE_ARTIFACT_PATH', 'CHEN_RELEASE_CONTROL_DIRECTORY', 'CHEN_RELEASE_TRUST_DIRECTORY')) {
        $Configuration[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
}
Test-Configuration $Configuration $ExistingDeployment.IsPresent
