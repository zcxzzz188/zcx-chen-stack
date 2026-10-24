#requires -Version 7.0
[CmdletBinding()]
param(
    [hashtable]$Configuration,
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

function Test-Configuration([hashtable]$Values) {
    $profiles = @(([string]$Values['SPRING_PROFILES_ACTIVE']).Split(',') | ForEach-Object { $_.Trim() })
    if ($profiles -cnotcontains 'rag') { throw 'SPRING_PROFILES_ACTIVE must explicitly include rag.' }
    if ([string]$Values['CHEN_RELEASE_PROJECT_NAME'] -cnotmatch '^chen-release-[a-z0-9][a-z0-9_-]{0,50}$') {
        throw 'A dedicated CHEN_RELEASE_PROJECT_NAME starting with chen-release- is required.'
    }
    $collection = [string]$Values['CHEN_RAG_QDRANT_COLLECTION']
    if ($collection -cnotmatch '^chen_[a-z0-9_]{1,90}$' -or $collection -ceq 'chen_platform_help_v1') {
        throw 'Set CHEN_RAG_QDRANT_COLLECTION to an explicit new release target.'
    }
    if (-not (Read-Boolean $Values 'CHEN_RELEASE_NEW_COLLECTION_CONFIRMED')) {
        throw 'CHEN_RELEASE_NEW_COLLECTION_CONFIRMED must explicitly attest a fresh target.'
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
    if ($releaseEnabled -and -not $ragEnabled) {
        throw 'Enabled release also requires CHEN_RAG_ENABLED=true.'
    }
    if ($ragEnabled -and [string]::IsNullOrWhiteSpace($embeddingKey)) {
        throw 'Enabled RAG requires its independent Embedding API key.'
    }
    if ($releaseEnabled) {
        foreach ($name in @('MANIFEST_PATH', 'APPROVAL_PATH', 'PUBLIC_KEY_PATH', 'OPERATOR', 'ENVIRONMENT', 'ARTIFACT_PATH')) {
            if ([string]::IsNullOrWhiteSpace([string]$Values["CHEN_RAG_RELEASE_$name"])) {
                throw "Enabled release requires CHEN_RAG_RELEASE_$name."
            }
        }
    }
    [pscustomobject]@{
        Result = 'CONFIGURATION_ONLY_PASS'
        RagEnabled = $ragEnabled
        ReleaseEnabled = $releaseEnabled
        QdrantConnection = $(if ($local) { 'LOOPBACK_WITH_KEY' } else { 'TLS_WITH_KEY' })
        TargetCollection = $collection
        TargetCollectionExistence = 'NOT_VERIFIED_NO_NETWORK_CALLS'
        ApprovalAndArtifactValidity = 'NOT_VERIFIED_BY_THIS_SCRIPT'
    }
}

if ($SelfTest) {
    $baseline = @{
        SPRING_PROFILES_ACTIVE = 'docker,rag'; CHEN_RELEASE_PROJECT_NAME = 'chen-release-selftest'
        CHEN_RAG_QDRANT_COLLECTION = 'chen_release_selftest'; CHEN_RELEASE_NEW_COLLECTION_CONFIRMED = 'true'
        CHEN_RAG_QDRANT_HOST = '127.0.0.1'; CHEN_RAG_QDRANT_API_KEY = 'synthetic-qdrant'
        CHEN_RAG_EMBEDDING_API_KEY = 'synthetic-embedding'; SPRING_AI_OPENAI_API_KEY = 'synthetic-chat'
    }
    $result = Test-Configuration $baseline
    if ($result.RagEnabled -or $result.ReleaseEnabled) { throw 'Disabled defaults regression.' }
    $cases = @(
        @{ SPRING_PROFILES_ACTIVE = 'docker' }, @{ CHEN_RAG_QDRANT_COLLECTION = '' },
        @{ CHEN_RAG_QDRANT_COLLECTION = 'chen_platform_help_v1' },
        @{ CHEN_RELEASE_NEW_COLLECTION_CONFIRMED = 'false' },
        @{ CHEN_RAG_QDRANT_HOST = 'qdrant.example.invalid'; CHEN_RAG_QDRANT_TLS = 'false' },
        @{ CHEN_RAG_QDRANT_API_KEY = '' }, @{ CHEN_RAG_QDRANT_API_KEY = 'synthetic-chat' },
        @{ CHEN_RAG_EMBEDDING_API_KEY = 'synthetic-chat' }, @{ CHEN_RAG_QDRANT_GRPC_PORT = '65536' },
        @{ CHEN_RAG_ENABLED = 'maybe' }, @{ CHEN_RELEASE_PROJECT_NAME = 'chen-stack' },
        @{ CHEN_RAG_RELEASE_ENABLED = 'true' }, @{ CHEN_RAG_ENABLED = 'true'; CHEN_RAG_EMBEDDING_API_KEY = '' }
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
    $enabledRelease = $baseline.Clone()
    $enabledRelease['CHEN_RAG_ENABLED'] = 'true'
    $enabledRelease['CHEN_RAG_RELEASE_ENABLED'] = 'true'
    foreach ($name in @('MANIFEST_PATH', 'APPROVAL_PATH', 'PUBLIC_KEY_PATH', 'OPERATOR', 'ENVIRONMENT', 'ARTIFACT_PATH')) {
        $enabledRelease["CHEN_RAG_RELEASE_$name"] = 'synthetic-value-not-read'
    }
    Test-Configuration $enabledRelease | Out-Null
    $releaseWithoutRag = $enabledRelease.Clone()
    $releaseWithoutRag['CHEN_RAG_ENABLED'] = 'false'
    $rejected = $false
    try { Test-Configuration $releaseWithoutRag | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw 'Release without enabled RAG was accepted.' }
    [pscustomobject]@{ Result = 'SELF_TEST_PASS'; Checks = $cases.Count + 5; ExternalCalls = 0 }
    return
}

if (-not $PSBoundParameters.ContainsKey('Configuration')) {
    $Configuration = @{}
    foreach ($name in @('SPRING_PROFILES_ACTIVE', 'CHEN_RELEASE_PROJECT_NAME', 'CHEN_RELEASE_NEW_COLLECTION_CONFIRMED',
        'CHEN_RAG_ENABLED', 'CHEN_RAG_RELEASE_ENABLED', 'CHEN_RAG_QDRANT_HOST', 'CHEN_RAG_QDRANT_GRPC_PORT',
        'CHEN_RAG_QDRANT_TLS', 'CHEN_RAG_QDRANT_API_KEY', 'CHEN_RAG_QDRANT_COLLECTION',
        'CHEN_RAG_EMBEDDING_API_KEY', 'SPRING_AI_OPENAI_API_KEY', 'CHEN_RAG_RELEASE_MANIFEST_PATH',
        'CHEN_RAG_RELEASE_APPROVAL_PATH', 'CHEN_RAG_RELEASE_PUBLIC_KEY_PATH', 'CHEN_RAG_RELEASE_OPERATOR',
        'CHEN_RAG_RELEASE_ENVIRONMENT', 'CHEN_RAG_RELEASE_ARTIFACT_PATH')) {
        $Configuration[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
}
Test-Configuration $Configuration
