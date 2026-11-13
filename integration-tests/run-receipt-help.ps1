param([Parameter(Mandatory=$true)][ValidatePattern('^[a-z0-9]{1,10}$')][string]$RunTag,
    [switch]$UseExisting,
    [string]$Candidate = 'target/VoidClear-3.0.0-receipt-help-a17-candidate.jar')
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot)
$namespace = "receipt$RunTag"
$jar = $Candidate
if (Get-ChildItem target/platform-tests -Directory -Filter "$namespace-*" -ErrorAction SilentlyContinue) { throw 'Preserve prior worlds; choose a fresh RunTag' }
if (Test-Path "integration-tests/receipt-help-sweep-$namespace.json") { throw 'Preserve prior report; choose a fresh RunTag' }
if (!$UseExisting) {
    if (Test-Path $jar) { throw 'Candidate exists; preserve its bytes or use -UseExisting' }
    jar --create --file $jar -C target/classes .
    if ($LASTEXITCODE -ne 0) { throw 'Candidate packaging failed' }
}
powershell -NoProfile -ExecutionPolicy Bypass -File integration-tests/verify-candidate.ps1 -Jar $jar
if ($LASTEXITCODE -ne 0) { throw 'Candidate does not match compiled output' }
$probeOut = Join-Path $PWD 'target/probe/classes'
New-Item -ItemType Directory -Force -Path $probeOut | Out-Null
$probeCp = (Get-Content target/check-classpath.txt -Raw).Trim() + ';target/classes'
javac --release 21 -encoding UTF-8 -cp $probeCp -d $probeOut integration-tests/probe/Probe.java
if ($LASTEXITCODE -ne 0) { throw 'Probe compilation failed' }
Copy-Item integration-tests/probe/plugin.yml (Join-Path $probeOut 'plugin.yml')
jar --create --file target/probe/VoidClearProbe.jar -C $probeOut .
if ($LASTEXITCODE -ne 0) { throw 'Probe packaging failed' }
node integration-tests/receipt-help-sweep.cjs --self-check
if ($LASTEXITCODE -ne 0) { throw 'Feature test self-check failed' }
if (Get-NetTCPConnection -State Listen -LocalPort 33317 -ErrorAction SilentlyContinue) { throw 'Isolated DB port occupied' }
$base = 'D:/BtSoft/mysql/MySQL5.7'
$data = (Join-Path $PWD 'target/platform-tests/mysql-data').Replace('\','/')
$oldJar = $env:VOIDCLEAR_TEST_JAR
$oldNamespace = $env:VOIDCLEAR_TEST_NAMESPACE
$testDb = Start-Process "$base/bin/mysqld.exe" -ArgumentList @('--no-defaults',"--basedir=$base","--datadir=$data",'--port=33317','--bind-address=127.0.0.1',"--log-error=$data/mysql.err",'--skip-log-bin') -PassThru
try {
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($testDb.HasExited) { throw 'Isolated DB exited' }
        if (Get-NetTCPConnection -State Listen -LocalPort 33317 -ErrorAction SilentlyContinue) { $ready = $true; break }
        Start-Sleep -Milliseconds 500
    }
    if (!$ready) { throw 'Isolated DB startup timed out' }
    $env:VOIDCLEAR_TEST_JAR = "../$jar"
    $env:VOIDCLEAR_TEST_NAMESPACE = $namespace
    node integration-tests/receipt-help-sweep.cjs
    if ($LASTEXITCODE -ne 0) { throw 'Feature integration failed' }
} finally {
    $env:VOIDCLEAR_TEST_JAR = $oldJar
    $env:VOIDCLEAR_TEST_NAMESPACE = $oldNamespace
    if (!$testDb.HasExited) {
        & "$base/bin/mysqladmin.exe" --protocol=tcp --host=127.0.0.1 --port=33317 --user=root shutdown
        if (!$testDb.WaitForExit(15000)) { throw 'Owned isolated DB did not stop' }
    }
}
