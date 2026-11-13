param([Parameter(Mandatory=$true)][string]$Jar)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path $Jar))
try {
    $classes = @($archive.Entries | Where-Object { $_.FullName.EndsWith('.class') })
    $compiled = @(Get-ChildItem target/classes -Filter *.class -Recurse)
    if (!$classes.Count -or $classes.Count -ne $compiled.Count) { throw 'Packaged class count differs from compiler output' }
    foreach ($entry in $classes) {
        if (!$entry.FullName.StartsWith('net/voidclear/') -or $entry.FullName -match '(?:Check|Probe|Test)(?:\$|\.class)') {
            throw "Unexpected production class: $($entry.FullName)"
        }
        $file = Join-Path 'target/classes' $entry.FullName
        $stream = $entry.Open()
        try {
            $hasher = [Security.Cryptography.SHA256]::Create()
            try { $actual = [BitConverter]::ToString($hasher.ComputeHash($stream)).Replace('-', '') }
            finally { $hasher.Dispose() }
        } finally { $stream.Dispose() }
        if ($actual -ne (Get-FileHash $file -Algorithm SHA256).Hash) { throw "Class differs: $($entry.FullName)" }
    }
    if ($archive.Entries.FullName -contains 'plugin.yml') {
        $reader = New-Object IO.StreamReader($archive.GetEntry('plugin.yml').Open())
        try { if ($reader.ReadToEnd() -match 'VoidClearProbe|vcprobe') { throw 'Probe descriptor in production JAR' } }
        finally { $reader.Dispose() }
    } else { throw 'Plugin descriptor missing' }
    foreach ($resource in Get-ChildItem src/main/resources -File -Recurse) {
        $relative = $resource.FullName.Substring((Resolve-Path 'src/main/resources').Path.Length + 1).Replace('\', '/')
        $entry = $archive.GetEntry($relative)
        if ($null -eq $entry) { throw "Resource missing: $relative" }
        $stream = $entry.Open()
        try {
            $hasher = [Security.Cryptography.SHA256]::Create()
            try { $actual = [BitConverter]::ToString($hasher.ComputeHash($stream)).Replace('-', '') }
            finally { $hasher.Dispose() }
        } finally { $stream.Dispose() }
        if ($actual -ne (Get-FileHash (Join-Path 'target/classes' $relative) -Algorithm SHA256).Hash) { throw "Resource differs: $relative" }
    }
    Get-FileHash $Jar -Algorithm SHA256
    Write-Output "Production package verified: $($classes.Count) matching classes; no test/probe classes"
} finally { $archive.Dispose() }
