# Read oscar_p1_fixture, restore into oscar_p1_restore_drill, compare, then drop only that database.
# Password comes from DALMARU_DB_PASSWORD via PGPASSWORD. This file has no password.
$ErrorActionPreference = 'Stop'
$SourceDb = 'oscar_p1_fixture'
$TargetDb = 'oscar_p1_restore_drill'
$AdminDb = 'postgres'
$DbHost = 'localhost'
$DbPort = '15432'
$DbUser = 'postgres'
if ($SourceDb -ne 'oscar_p1_fixture' -or $TargetDb -ne 'oscar_p1_restore_drill') { throw 'Refusing unexpected database names.' }
if ([string]::IsNullOrWhiteSpace($env:DALMARU_DB_PASSWORD)) { throw 'DALMARU_DB_PASSWORD is not set.' }

$bin = 'C:\Program Files\PostgreSQL\18\bin'
$pgDump = Join-Path $bin 'pg_dump.exe'
$pgRestore = Join-Path $bin 'pg_restore.exe'
$psql = Join-Path $bin 'psql.exe'
foreach ($tool in @($pgDump, $pgRestore, $psql)) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Missing $tool" }
}

$root = (& git rev-parse --show-toplevel).Trim()
$temp = Join-Path $env:TEMP ('dalmaru-m13a-' + [guid]::NewGuid().ToString('n'))
if ($temp.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) { throw 'Dump directory is inside the worktree.' }
New-Item -ItemType Directory -Path $temp | Out-Null
$dump = Join-Path $temp 'fixture.dump'
$created = $false
$env:PGPASSWORD = $env:DALMARU_DB_PASSWORD
$env:PGCLIENTENCODING = 'UTF8'

function Invoke-Psql([string]$database, [string]$sql) {
    $output = & $psql --host=$DbHost --port=$DbPort --username=$DbUser --dbname=$database --no-password --set=ON_ERROR_STOP=1 --no-align --tuples-only --command=$sql
    if ($LASTEXITCODE -ne 0) { throw "psql exit $LASTEXITCODE on $database" }
    return @($output | ForEach-Object { "$_".Trim() } | Where-Object { $_ -ne '' })
}

function Get-Tables([string]$database) {
    return @(Invoke-Psql $database "SELECT n.nspname || '|' || c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE c.relkind = 'r' AND n.nspname NOT IN ('pg_catalog','information_schema') ORDER BY 1")
}

function Get-Fingerprint([string]$database, [string[]]$tables) {
    $lines = New-Object System.Collections.Generic.List[string]
    foreach ($table in $tables) {
        $parts = $table.Split('|', 2)
        $schema = $parts[0].Replace('"', '""')
        $name = $parts[1].Replace('"', '""')
        $sql = "SELECT count(*)::text || '|' || md5(coalesce(string_agg(t::text, E'\n' ORDER BY t::text), '')) FROM (SELECT * FROM `"$schema`".`"$name`") t"
        $value = @(Invoke-Psql $database $sql)
        if ($value.Count -ne 1) { throw "Fingerprint failed for $table" }
        $lines.Add("$($parts[0]).$($parts[1])|$($value[0])")
    }
    return $lines
}

try {
    $present = @(Invoke-Psql $AdminDb "SELECT datname FROM pg_database WHERE datname = 'oscar_p1_fixture'")
    if ($present -notcontains 'oscar_p1_fixture') { throw 'Source database oscar_p1_fixture is not on localhost:15432.' }

    $beforeTables = @(Get-Tables $SourceDb)
    $before = @(Get-Fingerprint $SourceDb $beforeTables)

    $dumpStarted = [DateTimeOffset]::UtcNow
    $dumpWatch = [Diagnostics.Stopwatch]::StartNew()
    & $pgDump --host=$DbHost --port=$DbPort --username=$DbUser --dbname=$SourceDb --format=custom --file=$dump --no-password
    if ($LASTEXITCODE -ne 0) { throw "pg_dump exit $LASTEXITCODE" }
    $dumpWatch.Stop()
    $dumpEnded = [DateTimeOffset]::UtcNow

    $after = @(Get-Fingerprint $SourceDb $beforeTables)
    $sourceSame = (@($before) -join "`n") -eq (@($after) -join "`n")
    if (-not $sourceSame) { throw 'Source fingerprint changed during the read-only dump.' }

    $rtoWatch = [Diagnostics.Stopwatch]::StartNew()
    Invoke-Psql $AdminDb "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'oscar_p1_restore_drill' AND pid <> pg_backend_pid()" | Out-Null
    Invoke-Psql $AdminDb 'DROP DATABASE IF EXISTS oscar_p1_restore_drill' | Out-Null
    Invoke-Psql $AdminDb 'CREATE DATABASE oscar_p1_restore_drill' | Out-Null
    $created = $true
    $restoreWatch = [Diagnostics.Stopwatch]::StartNew()
    & $pgRestore --host=$DbHost --port=$DbPort --username=$DbUser --dbname=$TargetDb --no-owner --no-privileges --exit-on-error --no-password $dump
    if ($LASTEXITCODE -ne 0) { throw "pg_restore exit $LASTEXITCODE" }
    $restoreWatch.Stop()
    $verifyWatch = [Diagnostics.Stopwatch]::StartNew()
    $restoredTables = @(Get-Tables $TargetDb)
    $tableSame = (@($beforeTables) -join "`n") -eq (@($restoredTables) -join "`n")
    $restored = @(Get-Fingerprint $TargetDb $beforeTables)
    $verifyWatch.Stop()
    $rtoWatch.Stop()
    $checksumSame = $tableSame -and ((@($before) -join "`n") -eq (@($restored) -join "`n"))

    $sourceRows = 0
    $lines = foreach ($line in $before) {
        $bits = $line.Split('|')
        $rows = [int]$bits[1]
        $sourceRows += $rows
        '{0} rows={1} md5={2}' -f $bits[0], $rows, $bits[2]
    }
    Write-Output ("dump_start_utc=" + $dumpStarted.ToString('yyyy-MM-ddTHH:mm:ss.fffZ'))
    Write-Output ("dump_end_utc=" + $dumpEnded.ToString('yyyy-MM-ddTHH:mm:ss.fffZ'))
    Write-Output ("dump_seconds=" + [Math]::Round($dumpWatch.Elapsed.TotalSeconds, 3))
    Write-Output ("restore_seconds=" + [Math]::Round($restoreWatch.Elapsed.TotalSeconds, 3))
    Write-Output ("verify_seconds=" + [Math]::Round($verifyWatch.Elapsed.TotalSeconds, 3))
    Write-Output ("rto_seconds=" + [Math]::Round($rtoWatch.Elapsed.TotalSeconds, 3))
    Write-Output ("tables=" + $beforeTables.Count)
    Write-Output ("source_rows=" + $sourceRows)
    Write-Output ("source_unchanged=" + $sourceSame)
    Write-Output ("checksum_match=" + $checksumSame)
    $lines | ForEach-Object { Write-Output $_ }
    if (-not $checksumSame) { throw 'Restored fingerprint does not match the source.' }
}
finally {
    try {
        if ($created) {
            & $psql --host=$DbHost --port=$DbPort --username=$DbUser --dbname=$AdminDb --no-password --set=ON_ERROR_STOP=1 --command="SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'oscar_p1_restore_drill' AND pid <> pg_backend_pid()"
            if ($LASTEXITCODE -ne 0) { throw "cleanup terminate exit $LASTEXITCODE" }
            & $psql --host=$DbHost --port=$DbPort --username=$DbUser --dbname=$AdminDb --no-password --set=ON_ERROR_STOP=1 --command="DROP DATABASE IF EXISTS oscar_p1_restore_drill"
            if ($LASTEXITCODE -ne 0) { throw "cleanup drop exit $LASTEXITCODE" }
        }
    }
    finally {
        Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
        if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Recurse -Force }
    }
}
