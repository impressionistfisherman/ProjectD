# Deploy-jar smoke for RunRespawnMixin. ASCII for Windows PowerShell 5.
# Uses a temp directory only. Does not read or write the repository server.properties and does not set online-mode.
$ErrorActionPreference = 'Stop'
$serverPid = 0
$temp = $null
function Say([string]$text) {
    if ($env:DALMARU_DB_PASSWORD) { $text = $text.Replace($env:DALMARU_DB_PASSWORD, '***') }
    Write-Output $text
}
function Fail([string]$reason) { Say "FAIL $reason"; exit 1 }
function Read-Shared([string]$path) {
    if (-not (Test-Path $path)) { return '' }
    $stream = [IO.File]::Open($path, 'Open', 'Read', 'ReadWrite')
    try { $reader = New-Object IO.StreamReader($stream); return $reader.ReadToEnd() } finally { if ($reader) { $reader.Dispose() } else { $stream.Dispose() } }
}
try {
    if ([string]::IsNullOrWhiteSpace($env:DALMARU_DB_PASSWORD)) { Fail 'database password is not configured' }
    if ($env:DALMARU_DB_NAME -notin @('oscar_p3_fixture')) { Fail 'explicit P3 fixture required' }
    $root = Split-Path -Parent $PSScriptRoot
    $project = Join-Path $root 'server-project'
    $live = [IO.Path]::GetFullPath('C:\Users\gusdl\OneDrive\Desktop\Dalmaru').TrimEnd('\')
    $here = [IO.Path]::GetFullPath($root).TrimEnd('\')
    if ($here.Equals($live, [StringComparison]::OrdinalIgnoreCase)) { Fail 'refusing to run in the live Dalmaru checkout' }
    Push-Location $project
    & .\gradlew.bat --no-daemon assemble
    if ($LASTEXITCODE -ne 0) { Fail "assemble exit $LASTEXITCODE" }
    Pop-Location
    $jar = Get-ChildItem (Join-Path $project 'build\libs') -Filter 'dalmaru-*.jar' | Where-Object { $_.Name -notmatch 'sources|dev' } | Select-Object -First 1
    if ($null -eq $jar) { Fail 'deploy jar missing' }
    $port = 35565
    $listen = netstat -ano | Select-String ":$port\s"
    if ($listen) { Fail "port $port is already in use" }
    $temp = Join-Path $env:TEMP ("dalmaru-m14prep-" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path (Join-Path $temp 'mods') | Out-Null
    Copy-Item $jar.FullName (Join-Path $temp 'mods\dalmaru.jar')
    $api = Join-Path $temp 'mods\fabric-api.jar'
    Invoke-WebRequest -UseBasicParsing -Uri 'https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.155.3%2B26.1.2/fabric-api-0.155.3%2B26.1.2.jar' -OutFile $api
    Invoke-WebRequest -UseBasicParsing -Uri 'https://meta.fabricmc.net/v2/versions/loader/26.1.2/0.19.5/1.1.2/server/jar' -OutFile (Join-Path $temp 'server.jar')
    Set-Content -Encoding ascii -Path (Join-Path $temp 'eula.txt') -Value 'eula=true'
    Set-Content -Encoding ascii -Path (Join-Path $temp 'server.properties') -Value "server-port=$port"
    $out = Join-Path $temp 'stdout.log'
    $err = Join-Path $temp 'stderr.log'

    $proc = Start-Process -FilePath 'java' -ArgumentList @('-Xmx1G','-Dmixin.debug.verbose=true','-jar','server.jar','nogui') -WorkingDirectory $temp -RedirectStandardOutput $out -RedirectStandardError $err -PassThru -WindowStyle Hidden
    $serverPid = $proc.Id
    if ($serverPid -eq 14160) { Fail 'refusing to track the live server process' }
    $deadline = (Get-Date).AddMinutes(6)
    $sawMixin = $false
    $sawReady = $false
    $sawError = $false
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        $text = ''
        $text = (Read-Shared $out) + (Read-Shared $err)
        if ($text -match 'RunRespawnMixin') { $sawMixin = $true }
        if ($text -match 'Done \(') { $sawReady = $true }
        if ($text -match 'MixinApplyError|InvalidInjectionException|InjectionError|failed injection check|Mixin apply failed|does not match any targets') { $sawError = $true; break }
        if ($sawMixin -and $sawReady) { break }
        if ($proc.HasExited -and -not $sawMixin) { break }
    }
    if ($sawError -or -not $sawMixin -or -not $sawReady) {
        $note = if ($sawError) { 'injection error in server log' } elseif (-not $sawMixin) { 'RunRespawnMixin apply line missing' } else { 'server did not report ready' }
        foreach ($line in ($text -split "`n")) {
            if ($line -match 'RunRespawnMixin|MixinApply|Injection|ERROR|Exception|Done \(') { Say $line.Trim() }
        }
        Fail $note
    }
    Say 'PASS RunRespawnMixin applied and no injection error'
    exit 0
}
finally {
    if ($serverPid -ne 0 -and $serverPid -ne 14160) {
        & taskkill.exe /PID $serverPid /T /F 2>$null | Out-Null
    }
    if ($temp -and (Test-Path $temp)) { Remove-Item -Recurse -Force $temp -ErrorAction SilentlyContinue }
}
