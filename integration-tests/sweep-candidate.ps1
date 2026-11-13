param([string]$Candidate = '../target/VoidClear-3.0.0-sweep-candidate.jar', [ValidateSet('sweep','player','cuts','claims','focused','full','platforms','sessions')][string]$Mode = 'sweep')
# Starts only its owned loopback MySQL; never controls the system service.
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$port = 33317
if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw 'Isolated MySQL port already in use' }
$base = 'D:/BtSoft/mysql/MySQL5.7'
$data = (Join-Path $PWD 'target/platform-tests/mysql-data').Replace('\', '/')
if (!(Test-Path (Join-Path $data 'mysql'))) { throw 'Prepared isolated MySQL data directory missing' }
$mysql = Start-Process -FilePath "$base/bin/mysqld.exe" -ArgumentList @('--no-defaults', "--basedir=$base", "--datadir=$data", '--port=33317', '--bind-address=127.0.0.1', "--log-error=$data/mysql.err", '--skip-log-bin') -PassThru
try {
    $ready = $false
    for ($i = 0; $i -lt 60; $i++) {
        if ($mysql.HasExited) { throw 'Isolated MySQL exited before startup' }
        if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { $ready = $true; break }
        Start-Sleep -Milliseconds 500
    }
    if (!$ready) { throw 'Isolated MySQL startup timed out' }
    $env:VOIDCLEAR_TEST_JAR = $Candidate
    if ($Mode -eq 'cuts') { node integration-tests/player-cuts.cjs }
    elseif ($Mode -eq 'claims') { node integration-tests/player-claim-cuts.cjs }
    elseif ($Mode -eq 'platforms') { node integration-tests/platforms.cjs all }
    elseif ($Mode -eq 'sessions') { node integration-tests/player-session-exclusive.cjs }
    elseif ($Mode -eq 'full') { node integration-tests/resilience.cjs }
    else { node integration-tests/resilience.cjs $Mode }
    if ($LASTEXITCODE -ne 0) { throw "$Mode crash acceptance failed" }
} finally {
    if (!$mysql.HasExited) {
        & "$base/bin/mysqladmin.exe" --protocol=tcp --host=127.0.0.1 --port=33317 --user=root shutdown
        if (!$mysql.WaitForExit(15000)) { Stop-Process -Id $mysql.Id; throw 'Owned isolated MySQL required forced shutdown' }
    }
}
