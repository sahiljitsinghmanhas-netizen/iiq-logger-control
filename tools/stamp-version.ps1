# Copy the version out of manifest.xml and into Build.java.
#
# Each host publishes the version its sync service is actually RUNNING, which
# on a cluster is not always the version installed: IdentityIQ builds a service
# executor once and holds the instance, so a host goes on running the code it
# started with until its JVM restarts. Comparing the two is what turns that
# silent difference into something the page can name, and it only works if the
# constant cannot drift from the manifest - hence generating it.
#
# This began as an inline powershell call inside build.bat. It failed on the
# caret continuations and quoting, printed an error into the middle of an
# otherwise successful build, and left the constant untouched - so the build
# said "Version: 2.49.2" while compiling "0.0.0-not-stamped" into the jar. A
# file that can be run and checked on its own is worth more than a clever line.

param(
    [Parameter(Mandatory = $true)][string]$Manifest,
    [Parameter(Mandatory = $true)][string]$BuildJava
)

$ErrorActionPreference = "Stop"

$mf = [IO.File]::ReadAllText($Manifest)
$m = [Regex]::Match($mf, '<Plugin[^>]*?\sversion="([0-9][0-9A-Za-z.\-]*)"', 'Singleline')
if (-not $m.Success) { throw "no version attribute on <Plugin> in $Manifest" }
$version = $m.Groups[1].Value

$src = [IO.File]::ReadAllText($BuildJava)
$out = [Regex]::Replace($src, 'VERSION = "[^"]*"', ('VERSION = "' + $version + '"'))
if ($out -eq $src -and $src -notmatch [Regex]::Escape('VERSION = "' + $version + '"')) {
    throw "could not find the VERSION constant to stamp in $BuildJava"
}

# WriteAllText with a BOM-less encoding: Set-Content -Encoding UTF8 writes a
# byte order mark on Windows PowerShell, and a BOM in a .java file is a compile
# error waiting for whoever changes the build next.
[IO.File]::WriteAllText($BuildJava, $out, (New-Object Text.UTF8Encoding $false))

# Read it back. The whole reason this file exists is that the previous version
# reported success without doing anything.
$check = [IO.File]::ReadAllText($BuildJava)
if ($check -notmatch [Regex]::Escape('VERSION = "' + $version + '"')) {
    throw "stamped $version but the file does not contain it"
}
Write-Host "Version: $version (stamped into Build.java)"
