param([switch]$SkipDatabase)
$ErrorActionPreference='Stop'
$repoRoot=Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repoRoot
New-Item -ItemType Directory -Path '.local/m2' -Force | Out-Null
$argsForDocker=@('run','--rm','-v',"${repoRoot}:/workspace",'-v',"${repoRoot}/.local/m2:/root/.m2",'-w','/workspace')
if (-not $SkipDatabase) {
    if (-not (Test-Path -LiteralPath '.env')) { throw 'Run scripts/start-local.ps1 -DependenciesOnly before database tests.' }
    $settings=@{}
    Get-Content -LiteralPath '.env' | ForEach-Object { if ($_ -match '^([A-Z_]+)=(.*)$') { $settings[$Matches[1]]=$Matches[2] } }
    $argsForDocker+=@('-e','SMARTGN_TEST_DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/smartgn-management-db',
        '-e',('SMARTGN_TEST_DATABASE_USERNAME='+$settings.SPRING_DATASOURCE_USERNAME),
        '-e',('SMARTGN_TEST_DATABASE_PASSWORD='+$settings.SPRING_DATASOURCE_PASSWORD))
}
$argsForDocker+=@('maven:3.9.11-eclipse-temurin-21','mvn','-B','--no-transfer-progress','verify')
& docker @argsForDocker
if ($LASTEXITCODE -ne 0) { throw 'Maven verification failed' }
