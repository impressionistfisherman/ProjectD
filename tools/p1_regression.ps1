# P1 journal/client/replay regression, then the deploy-jar mixin smoke.
# ASCII for Windows PowerShell 5. Task names are read from build.gradle.
$ErrorActionPreference = 'Stop'
$log = $null
$err = $null
$init = $null
function Say([string]$text) {
    if ($env:DALMARU_DB_PASSWORD) { $text = $text.Replace($env:DALMARU_DB_PASSWORD, '***') }
    Write-Output $text
}
try {
    if ([string]::IsNullOrWhiteSpace($env:DALMARU_DB_PASSWORD)) { Say 'FAIL database password is not configured'; exit 1 }
    if ($env:DALMARU_DB_NAME -notin @('oscar_p1_fixture','oscar_p2_fixture')) { Say 'FAIL explicit P1 or P2 fixture required'; exit 1 }
    $root = Split-Path -Parent $PSScriptRoot
    $project = Join-Path $root 'server-project'
    $live = [IO.Path]::GetFullPath('C:\Users\gusdl\OneDrive\Desktop\Dalmaru').TrimEnd('\')
    $here = [IO.Path]::GetFullPath($root).TrimEnd('\')
    if ($here.Equals($live, [StringComparison]::OrdinalIgnoreCase)) { Say 'FAIL refusing to run in the live Dalmaru checkout'; exit 1 }
    $gradleFile = Join-Path $project 'build.gradle'
    $registered = [regex]::Matches((Get-Content -Raw $gradleFile), "tasks\.register\('([^']+)'") | ForEach-Object { $_.Groups[1].Value }
    $tasks = @($registered | Where-Object { $_ -match '^verify.*Journal$' -or $_ -match '^verifyRequestJournal' -or $_ -match '^verifyClient' -or $_ -match '^verifyQuest' -or $_ -eq 'verifyProtocolVersion' -or $_ -eq 'verifyEnhancementReplay' -or $_ -eq 'verifyRunAutoRespawnSafety' -or $_ -in @('verifyStarterSupply','verifyGrowthRewardGrant','verifyCharacterDeletion','verifyPaidStatReset','verifyVillageRoutes','verifyStoryInvestigationLink','verifyStoryQuestCurve','verifyWaypointReturn','verifyTrialGrowth','verifyProfessionGate','verifyCharacterExperience','verifyCharacterSkill','verifyCharacterProfession') } | Select-Object -Unique)
    $expected = @($registered | Where-Object { $_ -match '^verify.*Journal$' -or $_ -match '^verifyRequestJournal' -or $_ -match '^verifyClient' -or $_ -match '^verifyQuest' -or $_ -eq 'verifyProtocolVersion' -or $_ -eq 'verifyEnhancementReplay' -or $_ -eq 'verifyRunAutoRespawnSafety' -or $_ -in @('verifyStarterSupply','verifyGrowthRewardGrant','verifyCharacterDeletion','verifyPaidStatReset','verifyVillageRoutes','verifyStoryInvestigationLink','verifyStoryQuestCurve','verifyWaypointReturn','verifyTrialGrowth','verifyProfessionGate','verifyCharacterExperience','verifyCharacterSkill','verifyCharacterProfession') })
    if ($tasks.Count -eq 0 -or $tasks.Count -ne $expected.Count) { Say 'FAIL task list does not match build.gradle'; exit 1 }
    $log = Join-Path $env:TEMP ("dalmaru-m14prep2-" + [guid]::NewGuid().ToString('N') + '.log')
    $err = Join-Path $env:TEMP ("dalmaru-m14prep2-" + [guid]::NewGuid().ToString('N') + '.err')
    $init = Join-Path $env:TEMP ("dalmaru-m14prep2-" + [guid]::NewGuid().ToString('N') + '.init.gradle')
    $quoted = ($tasks | ForEach-Object { "'" + ($_ -replace "'","") + "'" }) -join ','
    Set-Content -Encoding ASCII -Path $init -Value ("gradle.taskGraph.whenReady { graph -> def names = [" + $quoted + "] as Set; graph.allTasks.each { task -> if (names.contains(task.name)) task.outputs.upToDateWhen { false } } }")

    $gradleArgs = @('--no-daemon','--continue','--init-script', $init) + $tasks
    $proc = Start-Process -FilePath (Join-Path $project 'gradlew.bat') -ArgumentList $gradleArgs -WorkingDirectory $project -Wait -PassThru -NoNewWindow -RedirectStandardOutput $log -RedirectStandardError $err
    $text = ''
    if (Test-Path $log) { $text += [IO.File]::ReadAllText($log) }
    if (Test-Path $err) { $text += [IO.File]::ReadAllText($err) }
    $rows = @()
    $failed = $false
    foreach ($name in $tasks) {
        $ran = [regex]::IsMatch($text, "(?m)^> Task :$([regex]::Escape($name))\s*$")
        $skipped = [regex]::IsMatch($text, "(?m)^> Task :$([regex]::Escape($name)) (UP-TO-DATE|FROM-CACHE|NO-SOURCE|SKIPPED)")
        $bad = [regex]::IsMatch($text, "(?m)^> Task :$([regex]::Escape($name)) FAILED")
        $result = if ($ran -and -not $skipped -and -not $bad) { 'PASS' } else { 'FAIL' }
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
    if ($init -and (Test-Path $init)) { Remove-Item -Force $init -ErrorAction SilentlyContinue }
}
