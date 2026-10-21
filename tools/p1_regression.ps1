# P1 journal/client/replay regression, then the deploy-jar mixin smoke.
# ASCII for Windows PowerShell 5. Task names are read from build.gradle.
$ErrorActionPreference = 'Stop'
$log = $null
$err = $null
$init = $null
$lock = 'C:\Users\gusdl\OneDrive\Desktop\MunderDifflin\hive\locks\p3_full_regression.lock'
$ownsLock = $false
$lockToken = [guid]::NewGuid().ToString()
function Say([string]$text) {
    if ($env:DALMARU_DB_PASSWORD) { $text = $text.Replace($env:DALMARU_DB_PASSWORD, '***') }
    Write-Output $text
}
try {
    if ([string]::IsNullOrWhiteSpace($env:DALMARU_DB_PASSWORD)) { Say 'FAIL database password is not configured'; exit 1 }
    if ($env:DALMARU_DB_NAME -notin @('oscar_p3_fixture','grok_p3_fixture')) { Say 'FAIL explicit P3 fixture required'; exit 1 }
    $root = Split-Path -Parent $PSScriptRoot
    $project = Join-Path $root 'server-project'
    $live = [IO.Path]::GetFullPath('C:\Users\gusdl\OneDrive\Desktop\Dalmaru').TrimEnd('\')
    $here = [IO.Path]::GetFullPath($root).TrimEnd('\')
    if ($here.Equals($live, [StringComparison]::OrdinalIgnoreCase)) { Say 'FAIL refusing to run in the live Dalmaru checkout'; exit 1 }
    New-Item -ItemType Directory -Force -Path (Split-Path $lock) | Out-Null
    while (-not $ownsLock) {
        try {
            $fs = [IO.File]::Open($lock, 'CreateNew', 'Write', 'Read')
            $ownsLock = $true
            try {
                $metadata = @{name=$(if($env:AGENT_NAME){$env:AGENT_NAME}else{'local'});agent=$(if($env:AGENT_ID){$env:AGENT_ID}else{'local'});startedAt=[DateTime]::UtcNow.ToString('o');commit=(git -C $root rev-parse HEAD).Trim();dirty=[bool](git -C $root status --porcelain);token=$lockToken} | ConvertTo-Json -Compress
                $bytes = [Text.Encoding]::UTF8.GetBytes($metadata)
                $fs.Write($bytes, 0, $bytes.Length)
                $fs.Flush()
            } finally { $fs.Dispose() }
            Say 'LOCK_ACQUIRED'
        } catch [IO.IOException] {
            if ($ownsLock) { throw }
            if (-not (Test-Path -LiteralPath $lock)) { throw }
            if (([DateTime]::UtcNow - [IO.File]::GetLastWriteTimeUtc($lock)).TotalMinutes -ge 40) { Say 'FAIL regression lock is older than 40 minutes; notify god; do not remove'; exit 1 }
            Say 'WAIT_LOCK'
            Start-Sleep -Seconds 30
        }
    }
    $gradleFile = Join-Path $project 'build.gradle'
    $registered = [regex]::Matches((Get-Content -Raw $gradleFile), "tasks\.register\('([^']+)'") | ForEach-Object { $_.Groups[1].Value }
    $tasks = @($registered | Where-Object { $_ -match '^verify.*Journal$' -or $_ -match '^verifyRequestJournal' -or $_ -match '^verifyClient' -or $_ -match '^verifyQuest' -or $_ -eq 'verifyProtocolVersion' -or $_ -eq 'verifyEnhancementReplay' -or $_ -eq 'verifyRunAutoRespawnSafety' -or $_ -in @('verifyRunKickVote','verifyRunPartyMembership','verifyRunEntryPartyOpen','verifyBossPartyScaling','verifyBossEngineHealth','verifyBossPosture','verifyBossTelegraph','verifyRunMemberExit','verifyRunReconnectWindow','verifyP4RunProgression','verifyDungeonRewardIsolation','verifyDungeonNaturalSpawn','verifyRunExitRecovery','verifyP4LifecycleRecovery','verifyP4PlayAccess','verifyBeaconTowerMobs','verifyBeaconTowerTerrain','verifyRunLifecycleOwnership','verifyPremergeRuntime','verifyBrazierNeglect','verifySkillContent','verifyCombatConsistency','verifySoloCombatDefinitions','verifyBrazierChannel','verifyP4ContentContracts','verifyContentCatalog','verifyRunContentSnapshot','verifyMailboxAuctionIntegration','verifyMailboxClaim','verifyAuctionSettlement','verifyEnhancementTail','verifyRewardEligibility','verifyInheritanceShare','verifyRefinementKeep','verifyRefinementGrade','verifyWeaponSlots','verifyStarterSupply','verifyGrowthRewardGrant','verifyCharacterDeletion','verifyPaidStatReset','verifyVillageRoutes','verifyStoryInvestigationLink','verifyStoryQuestCurve','verifyWaypointReturn','verifyTrialGrowth','verifyProfessionGate','verifyCharacterExperience','verifyCharacterSkill','verifyCharacterProfession','verifySupplyStay','verifyAccountVault','verifyNpcShop','verifyGatherNode','verifyBasicCraft','verifyDirectTrade','verifyP5LifeProficiency','verifyP5LifeSpecialization','verifyP5CraftResearch','verifyP5CraftPrecision','verifyP5CraftOrders') } | Select-Object -Unique)
    $expected = @($registered | Where-Object { $_ -match '^verify.*Journal$' -or $_ -match '^verifyRequestJournal' -or $_ -match '^verifyClient' -or $_ -match '^verifyQuest' -or $_ -eq 'verifyProtocolVersion' -or $_ -eq 'verifyEnhancementReplay' -or $_ -eq 'verifyRunAutoRespawnSafety' -or $_ -in @('verifyRunKickVote','verifyRunPartyMembership','verifyRunEntryPartyOpen','verifyBossPartyScaling','verifyBossEngineHealth','verifyBossPosture','verifyBossTelegraph','verifyRunMemberExit','verifyRunReconnectWindow','verifyP4RunProgression','verifyDungeonRewardIsolation','verifyDungeonNaturalSpawn','verifyRunExitRecovery','verifyP4LifecycleRecovery','verifyP4PlayAccess','verifyBeaconTowerMobs','verifyBeaconTowerTerrain','verifyRunLifecycleOwnership','verifyPremergeRuntime','verifyBrazierNeglect','verifySkillContent','verifyCombatConsistency','verifySoloCombatDefinitions','verifyBrazierChannel','verifyP4ContentContracts','verifyContentCatalog','verifyRunContentSnapshot','verifyMailboxAuctionIntegration','verifyMailboxClaim','verifyAuctionSettlement','verifyEnhancementTail','verifyRewardEligibility','verifyInheritanceShare','verifyRefinementKeep','verifyRefinementGrade','verifyWeaponSlots','verifyStarterSupply','verifyGrowthRewardGrant','verifyCharacterDeletion','verifyPaidStatReset','verifyVillageRoutes','verifyStoryInvestigationLink','verifyStoryQuestCurve','verifyWaypointReturn','verifyTrialGrowth','verifyProfessionGate','verifyCharacterExperience','verifyCharacterSkill','verifyCharacterProfession','verifySupplyStay','verifyAccountVault','verifyNpcShop','verifyGatherNode','verifyBasicCraft','verifyDirectTrade','verifyP5LifeProficiency','verifyP5LifeSpecialization','verifyP5CraftResearch','verifyP5CraftPrecision','verifyP5CraftOrders') })

    # P5 Oscar
    $tasks += 'verifyP5GrowthAdvancement'
    $expected += 'verifyP5GrowthAdvancement'
    # P5 Oscar
    $tasks += 'verifyP5GrowthRogueReset'
    $expected += 'verifyP5GrowthRogueReset'
    # P5 Oscar
    $tasks += 'verifyP5GrowthStoryGates'
    $expected += 'verifyP5GrowthStoryGates'
    # P5 Oscar
    $tasks += 'verifyP5CombatResources'
    $expected += 'verifyP5CombatResources'
    # P5 Oscar
    $tasks += 'verifyP5BranchSkillCatalog'
    $expected += 'verifyP5BranchSkillCatalog'
    # P5 Oscar
    $tasks += 'verifyP5BranchSkillExecution'
    $expected += 'verifyP5BranchSkillExecution'
    # P5 Oscar
    $tasks += 'verifyP5HousingLifecycle'
    $expected += 'verifyP5HousingLifecycle'
    # P5 Oscar
    $tasks += 'verifyP5HousingPermissions'
    $expected += 'verifyP5HousingPermissions'
    # P5 Oscar
    $tasks += 'verifyP5HousingCrops'
    $expected += 'verifyP5HousingCrops'
    # P5 Oscar
    $tasks += 'verifyP5CraftProductionQueue'
    $expected += 'verifyP5CraftProductionQueue'
    # P5 Oscar
    $tasks += 'verifyP5MarketStall'
    $expected += 'verifyP5MarketStall'
    # P5 Oscar
    $tasks += 'verifyP5MarketExchange'
    $expected += 'verifyP5MarketExchange'
    # P5 Oscar
    $tasks += 'verifyP5RunDifficulties'
    $expected += 'verifyP5RunDifficulties'
    # P5 Oscar
    $tasks += 'verifyP5GrowthReleaseCap'
    $expected += 'verifyP5GrowthReleaseCap'
    # P5 Grok
    $tasks += 'verifyP5LifeActionProficiency'
    $expected += 'verifyP5LifeActionProficiency'
    $tasks += 'verifyP5VillagePlaza'
    $expected += 'verifyP5VillagePlaza'
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
    if ($proc.ExitCode -ne 0) { Say $text }
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
    if ($ownsLock) {
        $current = [IO.File]::ReadAllText($lock) | ConvertFrom-Json
        if ($current.token -ne $lockToken) { throw 'LOCK_OWNER_CHANGED: refusing to remove another owner lock' }
        Remove-Item -LiteralPath $lock
        Say 'LOCK_RELEASED'
    }
    if ($log -and (Test-Path $log)) { Remove-Item -Force $log -ErrorAction SilentlyContinue }
    if ($err -and (Test-Path $err)) { Remove-Item -Force $err -ErrorAction SilentlyContinue }
    if ($init -and (Test-Path $init)) { Remove-Item -Force $init -ErrorAction SilentlyContinue }
}
