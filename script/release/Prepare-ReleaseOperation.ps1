#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$ManifestPath,
    [string]$ArtifactPath,
    [string]$ExpectedManifestSha256,
    [string]$ExpectedArtifactSha256,
    [string]$OperationOutputPath,
    [string]$OperationId,
    [string]$Operator,
    [string]$Environment,
    [int]$MaxEmbeddingRequests = -1,
    [string]$BudgetLedgerPath,
    [string]$BudgetId,
    [switch]$InitializeBudget,
    [switch]$ValidateOnly,
    [switch]$Confirm,
    [switch]$SelfTest
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
function Hash-Bytes([byte[]]$Bytes) { [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($Bytes)).ToLowerInvariant() }
function Get-OperationRecord {
    if ($Confirm -and $ValidateOnly) { throw 'Choose confirmation or validation only.' }
    foreach ($path in @($ManifestPath,$ArtifactPath)) {
        if (-not [IO.Path]::IsPathFullyQualified($path) -or -not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'Existing absolute release inputs are required.' }
    }
    if ($ExpectedManifestSha256 -cnotmatch '^[0-9a-f]{64}$' -or $ExpectedArtifactSha256 -cnotmatch '^[0-9a-f]{64}$' -or
        (Get-FileHash -LiteralPath $ManifestPath).Hash.ToLowerInvariant() -cne $ExpectedManifestSha256 -or
        (Get-FileHash -LiteralPath $ArtifactPath).Hash.ToLowerInvariant() -cne $ExpectedArtifactSha256) { throw 'Release hashes differ from the explicitly confirmed objects.' }
    if ((Get-Item -LiteralPath $ManifestPath).Length -notin 1..65536) { throw 'Manifest size invalid.' }
    $manifest = [Text.UTF8Encoding]::new($false,$true).GetString([IO.File]::ReadAllBytes($ManifestPath)) | ConvertFrom-Json -AsHashtable
    if ($OperationId -cnotmatch '^[A-Za-z0-9._-]{1,80}$' -or $Operator -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._:@/-]{0,127}$' -or
        $Environment -cnotmatch '^[A-Za-z0-9._-]{1,80}$' -or $manifest.environment -cne $Environment -or
        $manifest.artifactSha256 -cne $ExpectedArtifactSha256 -or $manifest.gitCommit -cnotmatch '^[0-9a-f]{40}$' -or
        $manifest.operation -cnotin @('PUBLISH','UPDATE','RECOVER','REVOKE') -or $MaxEmbeddingRequests -lt 0 -or
        ($manifest.operation -ceq 'REVOKE' -and $MaxEmbeddingRequests -ne 0)) { throw 'Controlled operator, operation or budget is invalid.' }
    if ($manifest.operation -ceq 'UPDATE' -and ($manifest.createCollection -or $manifest.initializeEmptyCatalog -or $MaxEmbeddingRequests -le 0)) {
        throw 'UPDATE requires an existing target and a positive bounded embedding budget.'
    }
    $zip=[IO.Compression.ZipFile]::OpenRead($ArtifactPath)
    try {
        $metadata=@($zip.Entries | Where-Object { $_.FullName -cin @('META-INF/chen-release-build.properties','BOOT-INF/classes/META-INF/chen-release-build.properties') })
        if($metadata.Count -ne 1){throw 'Ambiguous or missing JAR identity.'}
        $reader=[IO.StreamReader]::new($metadata[0].Open())
        try{$build=$reader.ReadToEnd()}finally{$reader.Dispose()}
        if($build -cnotmatch ('(?m)^gitCommit\s*=\s*'+[regex]::Escape($manifest.gitCommit)+'\s*$')){throw 'JAR commit differs.'}
        foreach($resource in @('knowledge/platform-help.json','knowledge/chenstack-product-capabilities.json')){
            $entry=$zip.GetEntry('BOOT-INF/classes/'+$resource)
            if($null -eq $entry){$entry=$zip.GetEntry($resource)}
            if($null -eq $entry -or $entry.Length -gt 2000000){throw 'Knowledge resource missing or too large.'}
            $stream=$entry.Open();$buffer=[IO.MemoryStream]::new()
            try{$stream.CopyTo($buffer);$bytes=$buffer.ToArray()}finally{$stream.Dispose();$buffer.Dispose()}
            if((Hash-Bytes $bytes) -cne $manifest.resourceSha256[$resource]){throw 'Knowledge resource hash differs.'}
            if($resource -ceq 'knowledge/platform-help.json'){$documents=[Text.UTF8Encoding]::new($false,$true).GetString($bytes)|ConvertFrom-Json}
        }
        $users=@($documents | Where-Object {$_.sourceId -cne 'help-history' -and -not $_.sourceId.StartsWith('help-admin-')})
        $topics=@($manifest.topics)
        if(@($documents).Count -ne 52 -or $users.Count -ne 39 -or $topics.Count -ne 39 -or
            @($topics.sourceId|Sort-Object -Unique).Count -ne 39 -or
            @(Compare-Object @($users.sourceId) @($topics.sourceId)).Count){throw 'The exact 39 user-topic scope differs.'}
        foreach($topic in $topics){$draft=@($users | Where-Object {$_.sourceId -ceq $topic.sourceId});if($draft.Count -ne 1 -or $draft[0].version -cne $topic.version -or $topic.textSha256 -cnotmatch '^[0-9a-f]{64}$'){throw 'Topic identity differs.'}}
    } finally {$zip.Dispose()}
    # 正式Java入口仍须核验正文规范化Hash、数据库、索引归属和预算；本记录不授予公开HTTP权限。
    return [ordered]@{schemaVersion=1;operationId=$OperationId;operator=$Operator;environment=$Environment;manifestSha256=$ExpectedManifestSha256;maxEmbeddingRequests=$MaxEmbeddingRequests;allowedOperation=$manifest.operation;confirmed=$true}
}
if($SelfTest){
    $temporary=Join-Path ([IO.Path]::GetTempPath()) ('chen-operator-selftest-'+[Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $temporary|Out-Null
    try{
        $ArtifactPath=Join-Path $temporary 'synthetic.jar';$ManifestPath=Join-Path $temporary 'manifest.json'
        $documents=@(1..39|ForEach-Object{@{sourceId='help-synthetic-'+$_;version='v1'}})+@(@{sourceId='help-history';version='v1'})+@(1..12|ForEach-Object{@{sourceId='help-admin-'+$_;version='v1'}})
        $resources=@{'knowledge/platform-help.json'=[Text.Encoding]::UTF8.GetBytes(($documents|ConvertTo-Json));'knowledge/chenstack-product-capabilities.json'=[Text.Encoding]::UTF8.GetBytes('{}')}
        $zip=[IO.Compression.ZipFile]::Open($ArtifactPath,[IO.Compression.ZipArchiveMode]::Create)
        try{foreach($name in $resources.Keys){$stream=$zip.CreateEntry($name).Open();try{$stream.Write($resources[$name])}finally{$stream.Dispose()}};$writer=[IO.StreamWriter]::new($zip.CreateEntry('META-INF/chen-release-build.properties').Open());try{$writer.Write('gitCommit='+('a'*40))}finally{$writer.Dispose()}}finally{$zip.Dispose()}
        $ExpectedArtifactSha256=(Get-FileHash -LiteralPath $ArtifactPath).Hash.ToLowerInvariant()
        $manifest=@{gitCommit='a'*40;artifactSha256=$ExpectedArtifactSha256;environment='isolated';operation='PUBLISH';resourceSha256=@{};topics=@($documents|Where-Object{$_.sourceId -cne 'help-history' -and -not $_.sourceId.StartsWith('help-admin-')}|ForEach-Object{@{sourceId=$_.sourceId;version='v1';textSha256='b'*64}})}
        foreach($name in $resources.Keys){$manifest.resourceSha256[$name]=Hash-Bytes $resources[$name]}
        $manifest|ConvertTo-Json -Depth 6|Set-Content -LiteralPath $ManifestPath -Encoding utf8NoBOM
        $ExpectedManifestSha256=(Get-FileHash -LiteralPath $ManifestPath).Hash.ToLowerInvariant();$OperationId='synthetic-1';$Operator='test-operator';$Environment='isolated';$MaxEmbeddingRequests=2
        $record=Get-OperationRecord;if(-not $record.confirmed -or $record.operationId -cne 'synthetic-1'){throw 'Positive record failed.'}
        $saved=$ExpectedManifestSha256;$ExpectedManifestSha256='0'*64;$rejected=$false;try{Get-OperationRecord|Out-Null}catch{$rejected=$true};if(-not $rejected){throw 'Hash mismatch accepted.'};$ExpectedManifestSha256=$saved
        $Operator='bad operator';$rejected=$false;try{Get-OperationRecord|Out-Null}catch{$rejected=$true};if(-not $rejected){throw 'Invalid operator accepted.'}
        $Operator='test-operator'
        $output=Join-Path $temporary 'operation.json';$ledger=Join-Path $temporary 'provider-attempts.ledger'
        $arguments=@{ManifestPath=$ManifestPath;ArtifactPath=$ArtifactPath;ExpectedManifestSha256=$ExpectedManifestSha256;ExpectedArtifactSha256=$ExpectedArtifactSha256;OperationOutputPath=$output;OperationId=$OperationId;Operator=$Operator;Environment=$Environment;MaxEmbeddingRequests=2;BudgetLedgerPath=$ledger;BudgetId='synthetic';InitializeBudget=$true;Confirm=$true}
        & $PSCommandPath @arguments | Out-Null
        if(-not(Test-Path -LiteralPath $output) -or [IO.File]::ReadAllText($ledger) -cne "CHEN_RELEASE_HTTP_BUDGET_V1|synthetic|64|20`n"){throw 'Confirmed record and budget creation failed.'}
        $before=(Get-FileHash -LiteralPath $ledger).Hash
        $rejected=$false;try{& $PSCommandPath @arguments|Out-Null}catch{$rejected=$true};if(-not $rejected -or (Get-FileHash -LiteralPath $ledger).Hash -cne $before){throw 'Existing operation or budget overwritten.'}
        $arguments['OperationOutputPath']=Join-Path $temporary 'second.json'
        $rejected=$false;try{& $PSCommandPath @arguments|Out-Null}catch{$rejected=$true};if(-not $rejected -or (Test-Path -LiteralPath $arguments.OperationOutputPath) -or (Get-FileHash -LiteralPath $ledger).Hash -cne $before){throw 'Existing budget was reset.'}
        [pscustomobject]@{Result='SELF_TEST_PASS';Checks=6;ExternalCalls=0;SignatureRequired=$false}
    }finally{$resolved=[IO.Path]::GetFullPath($temporary);$base=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar;if(-not $resolved.StartsWith($base,[StringComparison]::OrdinalIgnoreCase)-or [IO.Path]::GetFileName($resolved)-cnotmatch '^chen-operator-selftest-[0-9a-f]{32}$'){throw 'Unsafe self-test cleanup target.'};Remove-Item -LiteralPath $resolved -Recurse -Force}
    return
}
$record=Get-OperationRecord
if(-not $Confirm){[pscustomobject]@{Result='VALIDATE_ONLY_PASS';ManifestSha256=$ExpectedManifestSha256;ArtifactSha256=$ExpectedArtifactSha256;OperationId=$OperationId;Operator=$Operator;Operation=$record.allowedOperation;MaxEmbeddingRequests=$MaxEmbeddingRequests;FilesWritten=0;SignatureRequired=$false};return}
if(-not [IO.Path]::IsPathFullyQualified($OperationOutputPath) -or -not (Test-Path -LiteralPath ([IO.Path]::GetDirectoryName($OperationOutputPath)) -PathType Container)){throw 'Existing absolute output directory required.'}
if(Test-Path -LiteralPath $OperationOutputPath){throw 'Operation record already exists; do not overwrite.'}
if($InitializeBudget){
    if(-not [IO.Path]::IsPathFullyQualified($BudgetLedgerPath) -or $BudgetId -cnotmatch '^[A-Za-z0-9._-]{1,80}$' -or
        -not(Test-Path -LiteralPath ([IO.Path]::GetDirectoryName($BudgetLedgerPath)) -PathType Container)){throw 'Existing protected absolute budget directory and explicit budget ID required.'}
    $header=[Text.Encoding]::ASCII.GetBytes("CHEN_RELEASE_HTTP_BUDGET_V1|$BudgetId|64|20`n")
    $ledger=[IO.File]::Open($BudgetLedgerPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try{$ledger.Write($header);$ledger.Flush($true)}finally{$ledger.Dispose()}
}
$bytes=[Text.UTF8Encoding]::new($false).GetBytes(($record|ConvertTo-Json)+"`n")
$stream=[IO.File]::Open($OperationOutputPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
try{$stream.Write($bytes);$stream.Flush($true)}finally{$stream.Dispose()}
[pscustomobject]@{Result='CONTROLLED_OPERATION_RECORDED';OperationId=$OperationId;ManifestSha256=$ExpectedManifestSha256;OperationSha256=(Hash-Bytes $bytes);SignatureRequired=$false;DeploymentExecuted=$false;ProviderCalls=0}
