# ==============================================================================
# STOCKGUARD — Automated PostgreSQL Database Backup Script (PowerShell / Windows)
# ==============================================================================

param(
    [string]$ContainerName = $(if ($env:PG_CONTAINER_NAME) { $env:PG_CONTAINER_NAME } else { "stockguard-postgres" }),
    [string]$Database = $(if ($env:POSTGRES_DB) { $env:POSTGRES_DB } else { "stockguard" }),
    [string]$User = $(if ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { "stockguard" }),
    [string]$BackupDir = $(if ($env:BACKUP_DIR) { $env:BACKUP_DIR } else { "./backups" }),
    [int]$RetentionDays = 7
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $BackupDir)) {
    New-Item -ItemType Directory -Path $BackupDir -Force | Out-Null
}

$Timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$BackupFilename = "stockguard_backup_${Timestamp}.dump"
$TargetPath = Join-Path $BackupDir $BackupFilename

Write-Host "[$([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'))] Starting StockGuard database backup..." -ForegroundColor Cyan
Write-Host "  Database   : $Database"
Write-Host "  Container  : $ContainerName"
Write-Host "  Destination: $TargetPath"

# Execute pg_dump inside docker container and copy archive
$runningContainers = docker ps --format '{{.Names}}'
if ($runningContainers -contains $ContainerName) {
    Write-Host "  Executing pg_dump inside container $ContainerName..."
    $containerTemp = "/tmp/$BackupFilename"
    & docker exec $ContainerName pg_dump -U $User -d $Database -Fc -f $containerTemp
    & docker cp "${ContainerName}:${containerTemp}" $TargetPath
    & docker exec $ContainerName rm -f $containerTemp
} else {
    Write-Error "Docker container '$ContainerName' is not running."
}

if ((Test-Path $TargetPath) -and ((Get-Item $TargetPath).Length -gt 0)) {
    $sizeKb = [Math]::Round(((Get-Item $TargetPath).Length / 1KB), 2)
    Write-Host "[$([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'))] Backup completed successfully! ($sizeKb KB)" -ForegroundColor Green
} else {
    Write-Error "Backup file is empty or was not created: $TargetPath"
}

# Retention cleanup
if ($RetentionDays -gt 0) {
    $cutoff = (Get-Date).AddDays(-$RetentionDays)
    Get-ChildItem -Path $BackupDir -Filter "stockguard_backup_*.dump" | Where-Object { $_.LastWriteTime -lt $cutoff } | ForEach-Object {
        Write-Host "  Pruning old backup: $($_.Name)" -ForegroundColor Yellow
        Remove-Item $_.FullName -Force
    }
}
