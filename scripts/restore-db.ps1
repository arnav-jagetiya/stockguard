# ==============================================================================
# STOCKGUARD — PostgreSQL Database Restore Script (PowerShell / Windows)
# ==============================================================================

param(
    [Parameter(Mandatory=$true, Position=0)]
    [string]$BackupFile,

    [Parameter(Position=1)]
    [string]$TargetDatabase = $(if ($env:POSTGRES_DB) { $env:POSTGRES_DB } else { "stockguard" }),

    [string]$ContainerName = $(if ($env:PG_CONTAINER_NAME) { $env:PG_CONTAINER_NAME } else { "stockguard-postgres" }),
    [string]$User = $(if ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { "stockguard" })
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $BackupFile)) {
    Write-Error "Backup file does not exist: $BackupFile"
}

Write-Host "[$([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'))] Initiating database restore..." -ForegroundColor Cyan
Write-Host "  Source   : $BackupFile"
Write-Host "  Target DB: $TargetDatabase"
Write-Host "  Container: $ContainerName"

# Check if target database exists
$dbExists = docker exec $ContainerName psql -U $User -tAc "SELECT 1 FROM pg_database WHERE datname='$TargetDatabase'"
if (-not $dbExists -or $dbExists.ToString().Trim() -ne "1") {
    Write-Host "  Creating database '$TargetDatabase'..." -ForegroundColor Yellow
    docker exec $ContainerName createdb -U $User $TargetDatabase
    # Grant schema permissions so restoration succeeds cleanly in fresh databases
    docker exec $ContainerName psql -U $User -d $TargetDatabase -c "GRANT ALL ON SCHEMA public TO stockguard_owner; GRANT ALL ON SCHEMA public TO stockguard; GRANT ALL ON SCHEMA public TO stockguard_app;" | Out-Null
}

Write-Host "  Restoring dump into '$TargetDatabase'..." -ForegroundColor Cyan
$containerTemp = "/tmp/restore_" + [System.IO.Path]::GetFileName($BackupFile)
& docker cp $BackupFile "${ContainerName}:${containerTemp}"
& docker exec $ContainerName pg_restore -U $User -d $TargetDatabase --no-owner $containerTemp
& docker exec $ContainerName rm -f $containerTemp

Write-Host "[$([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'))] Database restore completed successfully for '$TargetDatabase'!" -ForegroundColor Green
