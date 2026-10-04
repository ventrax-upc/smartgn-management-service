param([switch]$SkipBuild, [switch]$DependenciesOnly)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repoRoot
function New-Secret { [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant() }
if (-not (Test-Path -LiteralPath '.env')) {
    $settings = @{
        PORT='8081'; POSTGRES_PORT='5432'; SPRING_DATASOURCE_URL='jdbc:postgresql://host.docker.internal:5432/smartgn-management-db';
        SPRING_DATASOURCE_USERNAME='smartgn_management'; SPRING_DATASOURCE_PASSWORD=(New-Secret);
        JWT_SECRET='local-dev-only-secret-change-me-0123456789-abcdef'; JWT_ISSUER='smartgn-iam-service'; JWT_AUDIENCE='';
        DEVICE_CREDENTIAL_KEY=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32));
        INTERNAL_SERVICE_TOKEN=(New-Secret); SIMULATOR_ADMIN_TOKEN=(New-Secret);
        TELEMETRY_BASE_URL='http://telemetry:8090'; EMQX_API_URL='http://broker:18083/api/v5';
        EMQX_API_KEY=(New-Secret); EMQX_API_SECRET=(New-Secret); EMQX_PASSWORD=(New-Secret);
        REDIS_HOST='redis'; REDIS_PORT='6379'; ALLOWED_ORIGINS='http://localhost:4200';
        MAINTENANCE_RETENTION_YEARS='5'; AUDIT_RETENTION_YEARS='5'
    }
    $settings.GetEnumerator() | Sort-Object Name | ForEach-Object { $_.Name+'='+$_.Value } | Set-Content -LiteralPath '.env' -Encoding utf8
    Write-Output 'Generated ignored local configuration. JWT matches the IAM development profile only.'
}
$settings = @{}
Get-Content -LiteralPath '.env' | ForEach-Object {
    if ($_ -match '^([A-Z_]+)=(.*)$') { $settings[$Matches[1]]=$Matches[2] }
}
New-Item -ItemType Directory -Path '.local' -Force | Out-Null
($settings.EMQX_API_KEY+':'+$settings.EMQX_API_SECRET) | Set-Content -LiteralPath '.local/emqx-api-keys' -Encoding utf8
docker info --format '{{.ServerVersion}}' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Docker engine is unavailable. Start Docker Desktop first.' }
$existing = docker ps -a --filter 'name=^/smartgn-postgres$' --format '{{.Names}}'
if (-not $existing) {
    docker compose --profile database up -d postgres
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL startup failed' }
} else {
    docker start smartgn-postgres | Out-Null
}
$ready = $false
for ($i=0; $i -lt 30; $i++) {
    docker exec smartgn-postgres pg_isready -U postgres -d postgres *> $null
    if ($LASTEXITCODE -eq 0) { $ready=$true; break }
    Start-Sleep -Seconds 1
}
if (-not $ready) { throw 'PostgreSQL is not ready' }
# Strict identifiers and generated secrets prevent interpolation of user-defined SQL.
$userName=$settings.SPRING_DATASOURCE_USERNAME
if ($userName -notmatch '^[a-z_][a-z0-9_]{0,62}$') { throw 'Database username must be a simple PostgreSQL identifier' }
$passwordSql=$settings.SPRING_DATASOURCE_PASSWORD.Replace("'", "''")
$roleExists=docker exec smartgn-postgres psql -U postgres -d postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname='$userName'"
if (-not $roleExists) {
    "CREATE ROLE $userName LOGIN PASSWORD '$passwordSql';" | docker exec -i smartgn-postgres psql -U postgres -d postgres -v ON_ERROR_STOP=1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot create Management database role' }
}
$dbExists=docker exec smartgn-postgres psql -U postgres -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='smartgn-management-db'"
if (-not $dbExists) {
    docker exec smartgn-postgres createdb -U postgres -O $userName smartgn-management-db
    if ($LASTEXITCODE -ne 0) { throw 'Cannot create Management database' }
}
# No database recreation, role password reset, or access to IAM tables.
('GRANT CONNECT, CREATE ON DATABASE "smartgn-management-db" TO {0};' -f $userName) | docker exec -i smartgn-postgres psql -U postgres -d postgres -v ON_ERROR_STOP=1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Cannot grant Management database access' }
docker compose up -d broker telemetry redis
if ($LASTEXITCODE -ne 0) { throw 'Local dependencies failed to start' }
if ($DependenciesOnly) { Write-Output 'Local dependencies are starting; application build was not requested.'; exit 0 }
if ($SkipBuild) { docker compose up -d management } else { docker compose up -d --build management }
if ($LASTEXITCODE -ne 0) { throw 'Management startup failed' }
$appReady=$false
for ($i=0; $i -lt 45; $i++) {
    try {
        $appHealth=Invoke-RestMethod -Uri ('http://127.0.0.1:'+$settings.PORT+'/actuator/health') -TimeoutSec 4
        if ($appHealth.status -eq 'UP') { $appReady=$true; break }
    } catch { }
    Start-Sleep -Seconds 1
}
if (-not $appReady) { throw 'Management did not become healthy. Inspect docker compose logs management without sharing secrets.' }
Write-Output ('Management ready at http://localhost:'+$settings.PORT+'/swagger-ui.html')
