#requires -Version 7.0
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$backend = Join-Path $repo 'chen-stack-backend'
Get-Command git, mvn, java -ErrorAction Stop | Out-Null

$gitCommit = (& git -C $repo rev-parse --verify HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $gitCommit -cnotmatch '^[0-9a-f]{40}$') {
    throw 'Cannot read a valid local Git HEAD.'
}

# Build the current sources; gitCommit identifies their base commit and does not certify a clean worktree.
& mvn --offline --file (Join-Path $backend 'pom.xml') "-Dchen.release.gitCommit=$gitCommit" verify
if ($LASTEXITCODE -ne 0) { throw 'Offline backend verification failed; no online fallback was attempted.' }

$artifact = Join-Path $backend 'target/zcx-chen-stack-backend-1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $artifact -PathType Leaf)) { throw 'Packaged backend artifact is missing.' }
$zip = [IO.Compression.ZipFile]::OpenRead($artifact)
try {
    $entries = @($zip.Entries | Where-Object {
        @('META-INF/chen-release-build.properties', 'BOOT-INF/classes/META-INF/chen-release-build.properties') -ccontains $_.FullName
    })
    if ($entries.Count -eq 0) { throw 'Packaged release build metadata is missing.' }
    if ($entries.Count -ne 1) { throw 'Packaged release build metadata is ambiguous.' }
    $entry = $entries[0]
    $reader = [IO.StreamReader]::new($entry.Open())
    try { $metadata = $reader.ReadToEnd() } finally { $reader.Dispose() }
    $match = [regex]::Match($metadata, '(?m)^gitCommit\s*=\s*([0-9a-f]{40})\s*$')
    if (-not $match.Success -or $match.Groups[1].Value -cne $gitCommit) {
        throw 'Packaged gitCommit does not match the requested local HEAD.'
    }
} finally { $zip.Dispose() }

[pscustomobject]@{
    Artifact = $artifact
    GitCommit = $gitCommit
    Sha256 = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant()
    Result = 'OFFLINE_VERIFIED_ARTIFACT'
}
