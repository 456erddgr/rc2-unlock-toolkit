# RC2 Toolbox build script (no Gradle, no Android SDK required)
# Usage:  .\_rc2\build.ps1 -Project _rc2\app -Out _rc2\app\out
param(
  [Parameter(Mandatory=$true)][string]$Project,
  [string]$Out = "",
  [string]$VersionName = "1.0",
  [int]$VersionCode = 1,
  [int]$MinSdk = 21,
  [int]$TargetSdk = 30,
  # "dx" (default) handles anonymous/non-static inner classes, which d8 cannot
  # dex on this machine. Use "d8" only for sources without outer captures.
  [ValidateSet("dx","d8")][string]$Dexer = "dx",
  [switch]$Release
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

# IMPORTANT: aapt2.exe (build-tools) cannot open ABSOLUTE paths that contain
# non-ASCII characters (e.g. a checkout under C:\Users\<中文用户名>\...).
# Relative paths work fine, and a SUBST drive letter also works, so we build
# through a dedicated drive letter to keep everything absolute and ASCII-safe.
$Drive = "R:"
$existing = (subst) | Where-Object { $_ -like "$Drive\:*" }
if($existing){
  $mapped = ($existing -split '=> ')[1].Trim()
  if($mapped -ne $Root){ subst $Drive /D | Out-Null; subst $Drive $Root | Out-Null }
} else {
  subst $Drive $Root | Out-Null
}
if(-not (subst | Where-Object { $_ -like "$Drive\:*" })){ throw "failed to map $Drive to $Root" }

$BT   = "$Drive\_rc2\toolchain\build-tools-34\android-14"
$DXJAR = "$Drive\_rc2\toolchain\bt29\android-10\lib\dx.jar"
$AJ   = "$Drive\_rc2\toolchain\platform-30\android-11\android.jar"
$JAVABIN = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot\bin"
$KS   = "$Drive\_rc2\keys\rc2toolbox.jks"

function Step($n,$m){ Write-Host "[$n] $m" -ForegroundColor Cyan }
function Check($what){ if($LASTEXITCODE -ne 0){ throw "$what failed (exit $LASTEXITCODE)" } }

# Rewrite the project path onto the ASCII-safe drive letter.
$Project = (Resolve-Path $Project).Path
if($Project.StartsWith($Root, [StringComparison]::OrdinalIgnoreCase)){
  $Project = $Drive + $Project.Substring($Root.Length)
} else {
  throw "project must live under $Root"
}
if(-not $Out){ $Out = Join-Path $Project "out" }
if(Test-Path $Out){ Remove-Item -Recurse -Force $Out }
foreach($d in @("flat","gen","classes","dex")){ New-Item -ItemType Directory -Force -Path (Join-Path $Out $d) | Out-Null }

# --- 1. compile resources -------------------------------------------------
Step 1 "aapt2 compile resources"
& "$BT\aapt2.exe" compile --dir (Join-Path $Project "res") -o (Join-Path $Out "flat")
Check "aapt2 compile"

# --- 2. link resources -> base apk ---------------------------------------
Step 2 "aapt2 link"
$flats = (Get-ChildItem (Join-Path $Out "flat") -Recurse -Filter *.flat).FullName
if(-not $flats){ throw "no compiled resources found" }
$linkArgs = @(
  "link","-o",(Join-Path $Out "base.apk"),
  "-I",$AJ,
  "--manifest",(Join-Path $Project "AndroidManifest.xml"),
  "--java",(Join-Path $Out "gen"),
  "--min-sdk-version",$MinSdk,
  "--target-sdk-version",$TargetSdk,
  "--version-code",$VersionCode,
  "--version-name",$VersionName
)
# Assets ride along in the link step. HiddenActivity reads hidden.tsv from here.
$assetDir = Join-Path $Project "assets"
if(Test-Path $assetDir){ $linkArgs += @("-A",$assetDir) }
$linkArgs += $flats
& "$BT\aapt2.exe" @linkArgs
Check "aapt2 link"

# --- 3. javac -------------------------------------------------------------
Step 3 "javac"
$srcs = @()
$srcDir = Join-Path $Project "src"
if(Test-Path $srcDir){ $srcs += (Get-ChildItem $srcDir -Recurse -Filter *.java).FullName }
$genDir = Join-Path $Out "gen"
if(Test-Path $genDir){ $srcs += (Get-ChildItem $genDir -Recurse -Filter *.java).FullName }
if(-not $srcs){ throw "no java sources" }
& "$JAVABIN\javac.exe" -encoding UTF-8 -source 8 -target 8 -nowarn -bootclasspath $AJ -classpath $AJ -d (Join-Path $Out "classes") @srcs
Check "javac"

# --- 4. dexing ------------------------------------------------------------
# NOTE: d8 (both R8 3.3.20-dev from build-tools 33 and R8 8.2.2-dev from
# build-tools 34) crashes with an internal NullPointerException on ANY class
# that carries the synthetic `this$0` outer-instance capture field, i.e. every
# anonymous / non-static inner class that touches its enclosing instance:
#   Error in X$1.class: java.lang.NullPointerException: Cannot invoke
#   "String.length()" because "<parameter1>" is null
# Verified minimal reproduction: `class Outer { void h(){} View.OnClickListener
# mk(){ return new View.OnClickListener(){ public void onClick(View v){ h(); } }; } }`
# -> Outer.class OK, Outer$1.class FAILS. A static nested class holding an
# explicit host reference, and an anonymous class that captures nothing, both
# dex fine.
# Legacy `dx` (build-tools 29) dexes those classes correctly, so we use it as
# the dexer. It is the only tool on this machine that can produce a dex for
# outer-capturing inner classes; d8 remains available via -Dexer d8 but only
# works for sources rewritten to lambdas/static-nested classes.
Step 4 "dexing ($Dexer)"
$classesDir = Join-Path $Out "classes"
$dexDir = Join-Path $Out "dex"
if($Dexer -eq "dx"){
  # dx resolves class-name-to-path itself, so it must be handed the *root* of the
  # class tree rather than individual .class paths (individual paths make it
  # abort with "class name (p/V$1) does not match path (...)").
  & "$JAVABIN\java.exe" -cp "$DXJAR" com.android.dx.command.Main --dex --min-sdk-version=$MinSdk --output=$dexDir $classesDir
  Check "dx"
} else {
  $cls = (Get-ChildItem $classesDir -Recurse -Filter *.class).FullName
  & cmd /c "`"$BT\d8.bat`" --min-api $MinSdk --output `"$dexDir`" --lib `"$AJ`" $($cls -join ' ')"
  Check "d8"
}
if(-not (Test-Path (Join-Path $dexDir "classes.dex"))){ throw "dexer produced no classes.dex" }

# --- 5. package -----------------------------------------------------------
Step 5 "package classes.dex into apk"
$unsigned = Join-Path $Out "unsigned.apk"
Copy-Item (Join-Path $Out "base.apk") $unsigned
& "$JAVABIN\jar.exe" uf $unsigned -C (Join-Path $Out "dex") classes.dex
Check "jar"

# --- 6. align + sign ------------------------------------------------------
Step 6 "zipalign + apksigner"
$aligned = Join-Path $Out "aligned.apk"
& "$BT\zipalign.exe" -f 4 $unsigned $aligned
Check "zipalign"
$signed = Join-Path $Out "signed.apk"
& cmd /c "`"$BT\apksigner.bat`" sign --ks `"$KS`" --ks-pass env:RC2_KS_PASS --key-pass env:RC2_KEY_PASS --out `"$signed`" `"$aligned`""
Check "apksigner"

# --- 7. verify ------------------------------------------------------------
Step 7 "verify"
& "$BT\aapt2.exe" dump badging $signed | Select-Object -First 3
& cmd /c "`"$BT\apksigner.bat`" verify --verbose $signed" 2>&1 | Select-Object -First 8

Write-Host ""
Write-Host "BUILD OK -> $signed" -ForegroundColor Green
Get-Item $signed | Select-Object FullName,Length | Format-List
