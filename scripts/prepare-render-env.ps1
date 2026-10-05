param([string]$IamRepository = (Join-Path (Split-Path -Parent $PSScriptRoot) '../smartgn-iam-service'))
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$outputDirectory = Join-Path $repoRoot '.local'
$iamOutput = Join-Path $outputDirectory 'render-iam.env'
$managementOutput = Join-Path $outputDirectory 'render-management.env'
$iamTemplate = Join-Path $IamRepository '.env.render.example'

if ((Test-Path -LiteralPath $iamOutput) -or (Test-Path -LiteralPath $managementOutput)) {
    if ((Test-Path -LiteralPath $iamOutput) -and (Test-Path -LiteralPath $managementOutput)) {
        Write-Host 'Existing Render configuration preserved. Edit these files to fill Neon credentials:'
        Write-Host $iamOutput
        Write-Host $managementOutput
        exit 0
    }
    throw 'Only one Render configuration exists. Restore its matching file; do not regenerate shared secrets.'
}
if (-not (Test-Path -LiteralPath $iamTemplate)) {
    throw 'IAM template not found. Pass -IamRepository with the IAM repository path.'
}

function New-RandomBase64([int]$Length) {
    $bytes = New-Object byte[] $Length
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    return [Convert]::ToBase64String($bytes)
}

$jwtSecret = New-RandomBase64 48
$credentialKey = New-RandomBase64 32
$iamContent = [IO.File]::ReadAllText($iamTemplate).Replace(
    'REPLACE_WITH_THE_SAME_RANDOM_SECRET_IN_BOTH_SERVICES', $jwtSecret)
$managementContent = [IO.File]::ReadAllText((Join-Path $repoRoot '.env.render.example')).Replace(
    'REPLACE_WITH_THE_SAME_RANDOM_SECRET_IN_BOTH_SERVICES', $jwtSecret).Replace(
    'REPLACE_WITH_BASE64_OF_32_RANDOM_BYTES', $credentialKey)
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$encoding = New-Object System.Text.UTF8Encoding $false
[IO.File]::WriteAllText($iamOutput, $iamContent, $encoding)
[IO.File]::WriteAllText($managementOutput, $managementContent, $encoding)
Write-Host 'Render configuration created with shared JWT and device encryption secrets. Fill Neon credentials in:'
Write-Host $iamOutput
Write-Host $managementOutput
Write-Host 'Keep these private files and the device key for later deployments. Never commit their contents.'
