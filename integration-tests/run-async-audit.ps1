param([Parameter(Mandatory=$true)][ValidatePattern('^a[0-9]+$')][string]$Candidate,
    [switch]$UseExisting, [ValidatePattern('^[a-z0-9]{1,8}$')][string]$RunTag)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot)
if (!$RunTag) { $RunTag = $Candidate }
if (Get-ChildItem target/platform-tests -Directory -Filter "as1110${RunTag}*" -ErrorAction SilentlyContinue) {
    throw 'RunTag already has evidence; choose a new tag to preserve the old worlds'
}
function Run-Node([string]$Script, [string]$Namespace, [string[]]$Extra = @()) {
    $env:VOIDCLEAR_TEST_NAMESPACE = $Namespace
    & node "integration-tests/$Script.cjs" @Extra
    if ($LASTEXITCODE -ne 0) { throw "$Script failed ($Namespace)" }
}
$jar = "target/VoidClear-3.0.0-async-db-$Candidate-candidate.jar"
if (!$UseExisting) {
if (Test-Path $jar) { throw 'Candidate name already exists; preserve its bytes' }
powershell -ExecutionPolicy Bypass -File check.ps1
if ($LASTEXITCODE -ne 0) { throw 'Offline checks failed' }
powershell -ExecutionPolicy Bypass -File integration-tests/run.ps1 -BuildOnly
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
Copy-Item target/VoidClear-3.0.0-single-server.jar $jar
}
Get-FileHash $jar -Algorithm SHA256
powershell -ExecutionPolicy Bypass -File integration-tests/verify-candidate.ps1 -Jar $jar
if ($LASTEXITCODE -ne 0) { throw 'Candidate package verification failed' }
$env:VOIDCLEAR_TEST_JAR = "../$jar"
if (Get-NetTCPConnection -State Listen -LocalPort 33317 -ErrorAction SilentlyContinue) { throw 'Isolated DB occupied' }
$base = 'D:/BtSoft/mysql/MySQL5.7'
$data = (Join-Path $PWD 'target/platform-tests/mysql-data').Replace('\','/')
$testDb = Start-Process "$base/bin/mysqld.exe" -ArgumentList @('--no-defaults',"--basedir=$base","--datadir=$data",'--port=33317','--bind-address=127.0.0.1',"--log-error=$data/mysql.err",'--skip-log-bin') -PassThru
try {
    $ready = $false
    for ($attempt=0; $attempt -lt 60; $attempt++) {
        if ($testDb.HasExited) { throw 'Isolated DB exited' }
        if (Get-NetTCPConnection -State Listen -LocalPort 33317 -ErrorAction SilentlyContinue) { $ready=$true; break }
        Start-Sleep -Milliseconds 500
    }
    if (!$ready) { throw 'DB startup timed out' }
    $cp = (Get-Content target/check-classpath.txt -Raw).Trim() + ';target/classes;target/test-classes'
    java -ea -cp $cp net.voidclear.v2.store.TrashMysqlCheck
    if ($LASTEXITCODE -ne 0) { throw 'JDBC checks failed' }
    Run-Node 'async-database-audit' "as1110${RunTag}x"
    $env:VOIDCLEAR_ASYNC_FOLIA = 'true'
    try { Run-Node 'async-database-audit' "as1110${RunTag}y" }
    finally { Remove-Item Env:VOIDCLEAR_ASYNC_FOLIA }
    $env:VOIDCLEAR_AUDIT_BACKEND = 'mysql'
    Run-Node 'cursor-death-audit' "as1110${RunTag}m"
    Remove-Item Env:VOIDCLEAR_AUDIT_BACKEND
    Run-Node 'platforms' "as1110${RunTag}p" @('all')
    Run-Node 'resilience' "as1110${RunTag}f" @('focused')
    Run-Node 'player-session-exclusive' "as1110${RunTag}s"
    $env:VOIDCLEAR_CLAIM_BACKENDS = 'sqlite,mysql'
    Run-Node 'player-claim-cuts' "as1110${RunTag}l"
    Run-Node 'player-cuts' "as1110${RunTag}c"
    Run-Node 'join-quit-tickets' "as1110${RunTag}j"
    Run-Node 'abandoned-pickup-audit' "as1110${RunTag}u"
} finally {
    if (!$testDb.HasExited) {
        & "$base/bin/mysqladmin.exe" --protocol=tcp --host=127.0.0.1 --port=33317 --user=root shutdown
        if (!$testDb.WaitForExit(15000)) { throw 'Isolated DB did not stop' }
    }
}
Run-Node 'maintenance-fixes' "as1110${RunTag}t"
Run-Node 'item-fences' "as1110${RunTag}i"
$env:VOIDCLEAR_DROP_PORT = '25652'
Run-Node 'drop-restart-audit' "as1110${RunTag}d"
Run-Node 'folia-drop-restart-audit' "as1110${RunTag}o"
Run-Node 'login-abort-audit' "as1110${RunTag}g"
$env:VOIDCLEAR_LIVE_DIR = "../target/platform-tests/as1110${RunTag}live"
Run-Node 'run' "as1110${RunTag}r"
Run-Node 'cursor-death-audit' "as1110${RunTag}h"
Run-Node 'survival-audit' "as1110${RunTag}v"
Run-Node 'bounded-soak-audit' "as1110${RunTag}k"
node integration-tests/verify-recovery-results-check.cjs
if ($LASTEXITCODE -ne 0) { throw 'Gate self-check failed' }
node integration-tests/verify-recovery-results.cjs $jar
if ($LASTEXITCODE -ne 0) { throw 'Candidate matrix incomplete' }
