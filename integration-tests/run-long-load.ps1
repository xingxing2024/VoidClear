param([Parameter(Mandatory=$true)][ValidatePattern('^a[0-9]+$')][string]$Candidate,
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-z0-9]{1,12}$')][string]$RunTag,
    [ValidateRange(30,7200)][int]$Seconds = 1800)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot)
$namespace = "load${RunTag}"
if (Get-ChildItem target/platform-tests -Directory -Filter "$namespace-*" -ErrorAction SilentlyContinue) { throw 'RunTag already has evidence; use a fresh name' }
if (Test-Path "integration-tests/long-load-$namespace.json") { throw 'Report already exists; preserve it' }
$jar = "target/VoidClear-3.0.0-async-db-$Candidate-candidate.jar"
if (!(Test-Path $jar)) { throw 'Existing immutable candidate required' }
powershell -ExecutionPolicy Bypass -File integration-tests/verify-candidate.ps1 -Jar $jar
if ($LASTEXITCODE -ne 0) { throw 'Candidate mismatch' }
node integration-tests/long-load.cjs --self-check
if ($LASTEXITCODE -ne 0) { throw 'Load script self-check failed' }
node integration-tests/verify-long-load.cjs --self-check
if ($LASTEXITCODE -ne 0) { throw 'Long-load gate self-check failed' }
$probeCp = (Get-Content target/check-classpath.txt -Raw).Trim() + ';target/classes'
javac --release 21 -encoding UTF-8 -cp $probeCp -d target/probe/classes integration-tests/probe/Probe.java
if ($LASTEXITCODE -ne 0) { throw 'Probe compilation failed' }
Copy-Item integration-tests/probe/plugin.yml target/probe/classes/plugin.yml
jar --create --file target/probe/VoidClearProbe.jar -C target/probe/classes .
if ($LASTEXITCODE -ne 0) { throw 'Probe packaging failed' }
foreach ($port in @(25653,25654,33317,33318)) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw "Isolated port occupied: $port" }
}
$env:VOIDCLEAR_TEST_NAMESPACE = $namespace
$env:VOIDCLEAR_TEST_JAR = "../$jar"
$env:VOIDCLEAR_LOAD_SECONDS = [string]$Seconds
$base = 'D:/BtSoft/mysql/MySQL5.7'
$data = (Join-Path $PWD 'target/platform-tests/mysql-data').Replace('\','/')
$testDb = Start-Process "$base/bin/mysqld.exe" -ArgumentList @('--no-defaults',"--basedir=$base","--datadir=$data",'--port=33317','--bind-address=127.0.0.1',"--log-error=$data/mysql.err",'--skip-log-bin') -PassThru
try {
    $ready = $false
    for ($attempt=0; $attempt -lt 60; $attempt++) {
        if ($testDb.HasExited) { throw 'Isolated database exited' }
        if (Get-NetTCPConnection -State Listen -LocalPort 33317 -ErrorAction SilentlyContinue) { $ready=$true; break }
        Start-Sleep -Milliseconds 500
    }
    if (!$ready) { throw 'Isolated database startup timed out' }
    node integration-tests/long-load.cjs
    if ($LASTEXITCODE -ne 0) { throw 'Long load failed; preserve all evidence' }
    if ($Seconds -ge 1800) {
        node integration-tests/verify-long-load.cjs $jar "integration-tests/long-load-$namespace.json"
        if ($LASTEXITCODE -ne 0) { throw 'Long-load report incomplete; preserve all evidence' }
    }
} finally {
    if (!$testDb.HasExited) {
        & "$base/bin/mysqladmin.exe" --protocol=tcp --host=127.0.0.1 --port=33317 --user=root shutdown
        if (!$testDb.WaitForExit(15000)) { throw 'Owned isolated database did not stop' }
    }
}
