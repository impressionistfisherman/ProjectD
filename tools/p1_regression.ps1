# P1 journal/client/replay regression, then the deploy-jar mixin smoke.
# ASCII for Windows PowerShell 5. Task names are read from build.gradle.
$ErrorActionPreference = 'Stop'
$log = $null
$err = $null
function Say([string]$text) {
    if ($env:DALMARU_DB_PASSWORD) { $text = $text.Replace($env:DALMARU_DB_PASSWORD, '***') }
    Write-Output $text
}
try {
    if ([string]::IsNullOrWhiteSpace($env:DALMARU_DB_PASSWORD)) { Say 'FAIL database password is not configured'; exit 1 }
    $root = Split-Path -Parent $PSScriptRoot
    $project = Join-Path $root 'server-project'
    $branch = git -C $root rev-parse --abbrev-ref HEAD
    if ($branch -ne 'grok/mdf-p1-m14prep') { Say "FAIL wrong branch $branch"; exit 1 }
    $gradleFile = Join-Path $project 'build.gradle'
    $registered = [regex]::Matches((Get-Content -Raw $gradleFile), "tasks\.register\('([^']+)'") | ForEach-Object { $_.Groups[1].Value }
    $tasks = @($registered | Where-Object { $_ -match '^verify.*Journal$' -or $_ -match '^verifyRequestJournal' -or $_ -match '^verifyClient' -or $_ -eq 'verifyEnhancementReplay' } | Select-Object -Unique)
    $expected = @($registered | Where-Object { $_ -match '^verify.*Journal$' -or $_ -match '^verifyRequestJournal' -or $_ -match '^verifyClient' -or $_ -eq 'verifyEnhancementReplay' })
    if ($tasks.Count -eq 0 -or $tasks.Count -ne $expected.Count) { Say 'FAIL task list does not match build.gradle'; exit 1 }
    $log = Join-Path $env:TEMP ("dalmaru-m14prep2-" + [guid]::NewGuid().ToString('N') + '.log')
    $err = Join-Path $env:TEMP ("dalmaru-m14prep2-" + [guid]::NewGuid().ToString('N') + '.err')
    $env:DALMARU_DB_NAME = 'oscar_p1_fixture'
    $gradleArgs = @('--no-daemon','--continue') + $tasks
    $proc = Start-Process -FilePath (Join-Path $project 'gradlew.bat') -ArgumentList $gradleArgs -WorkingDirectory $project -Wait -PassThru -NoNewWindow -RedirectStandardOutput $log -RedirectStandardError $err
    $text = ''
    if (Test-Path $log) { $text += [IO.File]::ReadAllText($log) }
    if (Test-Path $err) { $text += [IO.File]::ReadAllText($err) }
    $rows = @()
    $failed = $false
    foreach ($name in $tasks) {
        $ran = $text -match [regex]::Escape("> Task :$name")
        $bad = $text -match [regex]::Escape("> Task :$name FAILED")
        $result = if ($ran -and -not $bad) { 'PASS' } else { 'FAIL' }
        if ($result -eq 'FAIL') { $failed = $true }
        $rows += '{0,-36} {1}' -f $name, $result
    }
    $smoke = Join-Path $PSScriptRoot 'p1_integration_smoke.ps1'
    & powershell -NoProfile -ExecutionPolicy Bypass -File $smoke
    $smokeResult = if ($LASTEXITCODE -eq 0) { 'PASS' } else { 'FAIL' }
    if ($smokeResult -eq 'FAIL') { $failed = $true }
    $rows += '{0,-36} {1}' -f 'p1_integration_smoke.ps1', $smokeResult
    Say ('{0,-36} {1}' -f 'TASK','RESULT')
    foreach ($row in $rows) { Say $row }
    if ($failed -or $proc.ExitCode -ne 0) { exit 1 }
    exit 0
}
finally {
    if ($log -and (Test-Path $log)) { Remove-Item -Force $log -ErrorAction SilentlyContinue }
    if ($err -and (Test-Path $err)) { Remove-Item -Force $err -ErrorAction SilentlyContinue }
}
