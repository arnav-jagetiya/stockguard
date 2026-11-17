# STOCKGUARD — Database Backup, Restoration & Disaster Recovery Procedure

**Document Version:** 1.0.0  
**Status:** Authoritative  
**Target RPO (Recovery Point Objective):** <= 1 Hour  
**Target RTO (Recovery Time Objective):** <= 15 Minutes  

---

## 1. Backup Strategy Overview

STOCKGUARD employs PostgreSQL's native custom archive format (`pg_dump -Fc`) for automated daily and hourly database backups.

### Advantages of Custom Format (`-Fc`):
- **Integrated Compression:** Reduces disk footprint by 75-85%.
- **Granular Restoration:** Allows parallel restores, selective table restores, and schema-only or data-only extracts.
- **Integrity Validation:** Stores internal checksums for every table and data block.
- **Cross-Platform Compatibility:** Easily restored via `pg_restore` on Linux, macOS, or Windows.

---

## 2. Automated Backup Execution

### Linux / Production Cron
Automated backups are executed using [scripts/backup-db.sh](../../scripts/backup-db.sh):

```bash
# Execute manual backup
./scripts/backup-db.sh

# Environment variables accepted:
# - PG_CONTAINER_NAME (default: stockguard-postgres)
# - POSTGRES_DB       (default: stockguard)
# - POSTGRES_USER     (default: stockguard)
# - BACKUP_DIR        (default: ./backups)
# - BACKUP_RETENTION_DAYS (default: 7)
```

#### Hourly Backup Cron Schedule
Add to `/etc/cron.d/stockguard-db-backup`:
```cron
# Run database backup every hour at minute 0; retain 7 days locally
0 * * * * root /opt/stockguard/scripts/backup-db.sh >> /var/log/stockguard-backup.log 2>&1
```

### Windows / Development PowerShell
```powershell
powershell -ExecutionPolicy Bypass -File "scripts/backup-db.ps1"
```

---

## 3. Database Restoration Procedure

Restorations are performed using [scripts/restore-db.sh](../../scripts/restore-db.sh) or [scripts/restore-db.ps1](../../scripts/restore-db.ps1).

### Restoration Syntax:
```bash
# Linux / Bash
./scripts/restore-db.sh <path-to-dump-file> <target-database-name> [--force]

# Windows / PowerShell
powershell -ExecutionPolicy Bypass -File "scripts/restore-db.ps1" -BackupFile <path-to-dump-file> -TargetDatabase <target-database-name> [-Force]
```

> [!CAUTION]
> **Production Overwrite Safeguards:**
> - The target database parameter is **mandatory**. The script will fail fast if omitted.
> - The database name is validated strictly against regex `^[a-zA-Z0-9_]+$`.
> - If targeting the primary active database (`stockguard` or `$POSTGRES_DB`), the script demands either the explicit `--force` / `-Force` flag or interactive confirmation typing `OVERWRITE`. In non-interactive pipelines (e.g. CI/CD or cron), it immediately aborts with an error unless `--force` / `-Force` is passed.

### A. Isolated Disaster Recovery Verification Test (Non-Destructive)
To test a backup archive without touching the live application database:
```bash
# 1. Restore into a temporary isolated database
./scripts/restore-db.sh ./backups/stockguard_backup_20261117_111448.dump stockguard_restore_test

# 2. Run verification queries against stockguard_restore_test
docker exec stockguard-postgres psql -U stockguard -d stockguard_restore_test -c "
SELECT 
    COUNT(*) as total_balances,
    COUNT(CASE WHEN b.on_hand != COALESCE(l.ledger_on_hand, 0) THEN 1 END) as on_hand_discrepancies,
    COUNT(CASE WHEN b.reserved != COALESCE(l.ledger_reserved, 0) THEN 1 END) as reserved_discrepancies
FROM inventory_balances b
LEFT JOIN (
    SELECT warehouse_id, product_id, SUM(on_hand_delta) as ledger_on_hand, SUM(reserved_delta) as ledger_reserved
    FROM inventory_ledger_entries
    GROUP BY warehouse_id, product_id
) l ON b.warehouse_id = l.warehouse_id AND b.product_id = l.product_id;
"

# 3. Clean up test database
docker exec stockguard-postgres psql -U stockguard -d stockguard -c "DROP DATABASE stockguard_restore_test;"
```

### B. Full Production Database Recovery
In the event of total server loss or data corruption:
```bash
# 1. Stop backend application to prevent in-flight writes
docker compose -f docker-compose.prod.yml stop stockguard-app

# 2. Terminate active sessions and drop corrupted database
docker exec stockguard-postgres psql -U stockguard -d postgres -c "
SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'stockguard' AND pid <> pg_backend_pid();
DROP DATABASE IF EXISTS stockguard;
CREATE DATABASE stockguard OWNER stockguard;
"

# 3. Restore from latest verified backup archive with explicit --force
./scripts/restore-db.sh ./backups/stockguard_backup_latest.dump stockguard --force

# 4. Restart backend application
docker compose -f docker-compose.prod.yml start stockguard-app
```

---

## 4. Offsite Backup & Encryption Recommendations

- **Offsite Shipping:** Synchronize `./backups/` to an S3-compatible object store (e.g., AWS S3, Cloudflare R2, Google Cloud Storage) with object immutability (WORM retention) enabled.
- **At-Rest Encryption:** Encrypt backup archives before offsite transfer using GPG or age:
  ```bash
  gpg --symmetric --cipher-algo AES256 ./backups/stockguard_backup_*.dump
  ```
