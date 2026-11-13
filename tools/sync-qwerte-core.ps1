<#
  Copies the shared qwerte-core library into this plugin (a synced copy, not a dependency).

  qwerte-core/src/main/java      -> src/main/java       (only com/porter/qwerte/core)
  qwerte-core/src/main/resources -> src/main/resources  (only com/porter/qwerte/core, with the enchant-*.json tables)

  Stale files of the copy are removed. The Gradle build has the same copy as `./gradlew syncQwerteCore` and a drift check
  (`checkQwerteCore`) that fails the build when the copy differs from the library. Never edit the copy by hand: change
  Qwertze/qwerte-core, then run this.

  Usage: powershell -File tools/sync-qwerte-core.ps1 [-Core <path to qwerte-core>]
#>
param(
    [string]$Core = (Join-Path $PSScriptRoot '..\..\Qwertze\qwerte-core')
)
$ErrorActionPreference = 'Stop'
$plugin = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not (Test-Path (Join-Path $Core 'src\main'))) { throw "qwerte-core not found at $Core" }
$Core = (Resolve-Path $Core).Path
$prefix = 'com\porter\qwerte\core'
$changed = 0
foreach ($root in @('src\main\java', 'src\main\resources')) {
    $from = Join-Path (Join-Path $Core $root) $prefix
    $to = Join-Path (Join-Path $plugin $root) $prefix
    if (-not (Test-Path $from)) { throw ("the library has no " + $root + "\" + $prefix + " folder: " + $from + " (the copy is left alone)") }
    $names = @()
    if (Test-Path $from) { $names = @(Get-ChildItem $from -Recurse -File | ForEach-Object { $_.FullName.Substring($from.Length).TrimStart('\') }) }
    if (Test-Path $to) {
        Get-ChildItem $to -Recurse -File | ForEach-Object {
            $rel = $_.FullName.Substring($to.Length).TrimStart('\')
            if ($names -notcontains $rel) { Remove-Item $_.FullName; $changed++; Write-Host "removed $root\$prefix\$rel" }
        }
    }
    foreach ($rel in $names) {
        $src = Join-Path $from $rel
        $dst = Join-Path $to $rel
        $same = $false
        if (Test-Path $dst) {
            $a = [IO.File]::ReadAllText($src).Replace("`r`n", "`n")
            $b = [IO.File]::ReadAllText($dst).Replace("`r`n", "`n")
            $same = ($a -ceq $b)
        }
        if ($same) { continue }
        New-Item -ItemType Directory -Force (Split-Path $dst) | Out-Null
        Copy-Item $src $dst -Force
        $changed++
        Write-Host "copied $root\$prefix\$rel"
    }
}
Write-Host "qwerte-core synced from $Core : $changed file(s) changed."
