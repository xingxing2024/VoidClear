param([switch]$BuildOnly)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
mvn -o -q test-compile dependency:build-classpath '-Dmdep.outputFile=target/check-classpath.txt'
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
$probeOut = Join-Path $PWD 'target/probe/classes'
New-Item -ItemType Directory -Force -Path $probeOut | Out-Null
$probeCp = (Get-Content 'target/check-classpath.txt' -Raw).Trim() + ';target/classes'
javac --release 21 -encoding UTF-8 -cp $probeCp -d $probeOut integration-tests/probe/Probe.java
if ($LASTEXITCODE -ne 0) { throw 'Probe compilation failed' }
Copy-Item integration-tests/probe/plugin.yml (Join-Path $probeOut 'plugin.yml')
jar --create --file target/probe/VoidClearProbe.jar -C $probeOut .
if ($LASTEXITCODE -ne 0) { throw 'Probe packaging failed' }
jar --create --file target/VoidClear-3.0.0-single-server.jar -C target/classes .
if ($LASTEXITCODE -ne 0) { throw 'Plugin packaging failed' }
if ($BuildOnly) { exit 0 }
if (!$env:VOIDCLEAR_TEST_JAR) { $env:VOIDCLEAR_TEST_JAR = '../target/VoidClear-3.0.0-single-server.jar' }
node integration-tests/run.cjs
if ($LASTEXITCODE -ne 0) { throw 'Integration tests failed' }
