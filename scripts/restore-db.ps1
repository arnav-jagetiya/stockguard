# ==============================================================================
# STOCKGUARD — PostgreSQL Database Restore Script (PowerShell / Windows / Milestone 10 Hardened)
# ==============================================================================

param(
    [string]$BackupFile,
    [string]$TargetDatabase,
    [switch]$Force,
    [string]$ContainerName = $(if ($env:PG_CONTAINER_NAME) { $env:PG_CONTAINER_NAME } else { "stockguard-postgres" }),
    [string]$User = $(if ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { "postgres" })
)

$ErrorActionPreference = "Stop"

# Assert mandatory arguments
if (-not $BackupFile -or -not $TargetDatabase) {
    Write-Host "ERROR: Target database parameter is mandatory to prevent accidental overwrite of active data." -ForegroundColor Red
    Write-Host "Usage: .\scripts\restore-db.ps1 -BackupFile <backup-file.dump> -TargetDatabase <target-database-name> [-Force]"
    Write-Host "Example (safe test restore): .\scripts\restore-db.ps1 -BackupFile .\backups\stockguard_backup.dump -TargetDatabase stockguard_restore_test"
    Write-Host "Example (live database restore): .\scripts\restore-db.ps1 -BackupFile .\backups\stockguard_backup.dump -TargetDatabase stockguard -Force"
    exit 1
}

# 1. Assert backup file exists
if (-not (Test-Path $BackupFile)) {
    Write-Error "Backup file does not exist: $BackupFile"
    exit 1
}

# 2. Validate target database name syntax (alphanumeric and underscores only)
if ($TargetDatabase -notmatch '^[a-zA-Z0-9_]+$') {
    Write-Error "Invalid target database name '$TargetDatabase'. Only alphanumeric characters and underscores are permitted."
    exit 1
}

# 3. Active database overwrite protection
$activeDb = if ($env:POSTGRES_DB) { $env:POSTGRES_DB } else { "stockguard" }
if ($TargetDatabase -eq $activeDb) {
    if (-not $Force) {
        $isInteractive = [Environment]::UserInteractive -and -not [Console]::IsInputRedirected
        if ($isInteractive) {
            Write-Host "WARNING: Target database '$TargetDatabase' matches active production database '$activeDb'!" -ForegroundColor Yellow
            $confirmation = Read-Host "Type 'OVERWRITE' to proceed with restoring into the active database"
            if ($confirmation -ne "OVERWRITE") {
                Write-Host "Restoration aborted by operator." -ForegroundColor Red
                exit 1
            }
        } else {
            Write-Error "Refusing to restore into active database '$activeDb' in non-interactive mode without '-Force' switch."
            exit 1
        }
    } else {
        Write-Host "WARNING: Restoring directly into active database '$activeDb' with explicit -Force switch." -ForegroundColor Yellow
    }
}

Write-Host "[$([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'))] Initiating database restore..." -ForegroundColor Cyan
Write-Host "  Source   : $BackupFile"
Write-Host "  Target DB: $TargetDatabase"
Write-Host "  Container: $ContainerName"

# 4. Check if target database exists
$dbExists = docker exec $ContainerName psql -U $User -tAc "SELECT 1 FROM pg_database WHERE datname='$TargetDatabase'"
if (-not $dbExists -or $dbExists.ToString().Trim() -ne "1") {
    Write-Host "  Creating database '$TargetDatabase'..." -ForegroundColor Yellow
    docker exec $ContainerName createdb -U $User $TargetDatabase
    # Grant schema permissions so restoration succeeds cleanly in fresh databases
    docker exec $ContainerName psql -U $User -d $TargetDatabase -c "GRANT ALL ON SCHEMA public TO stockguard_owner; GRANT ALL ON SCHEMA public TO stockguard; GRANT ALL ON SCHEMA public TO stockguard_app;" | Out-Null
}

# 5. Restore dump into target database
Write-Host "  Restoring dump into '$TargetDatabase'..." -ForegroundColor Cyan
$containerTemp = "/tmp/restore_" + [System.IO.Path]::GetFileName($BackupFile)
& docker cp $BackupFile "${ContainerName}:${containerTemp}"
& docker exec $ContainerName pg_restore -U $User -d $TargetDatabase --no-owner $containerTemp
& docker exec $ContainerName rm -f $containerTemp

Write-Host "[$([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'))] Database restore completed successfully for '$TargetDatabase'!" -ForegroundColor Green
