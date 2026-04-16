param(
    [Parameter(Mandatory = $false)]
    [string]$ProjectRoot
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $scriptPath = $MyInvocation.MyCommand.Path
    if ([string]::IsNullOrWhiteSpace($scriptPath)) {
        $ProjectRoot = (Get-Location).Path
    } else {
        $ProjectRoot = Split-Path -Parent (Split-Path -Parent $scriptPath)
    }
}

function Read-SdkDirFromLocalProperties {
    param([string]$Root)
    $localProps = Join-Path $Root "local.properties"
    if (-not (Test-Path $localProps)) { return $null }
    $line = Get-Content $localProps -Encoding UTF8 | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
    if (-not $line) { return $null }
    $raw = ($line -split '=', 2)[1].Trim()
    if ([string]::IsNullOrWhiteSpace($raw)) { return $null }
    return ($raw -replace '\\\\', '\')
}

function First-ExistingPath {
    param([string[]]$Candidates)
    foreach ($path in $Candidates) {
        if ([string]::IsNullOrWhiteSpace($path)) { continue }
        if (Test-Path $path) { return (Resolve-Path $path).Path }
    }
    return $null
}

$sdkDir =
    First-ExistingPath @(
        $env:ANDROID_SDK_ROOT,
        $env:ANDROID_HOME,
        (Read-SdkDirFromLocalProperties -Root $ProjectRoot),
        "$env:LOCALAPPDATA\Android\Sdk"
    )

if (-not $sdkDir) {
    throw "Android SDK not found. Please set ANDROID_SDK_ROOT or local.properties sdk.dir."
}

$env:ANDROID_SDK_ROOT = $sdkDir
$env:ANDROID_HOME = $sdkDir

$sdkBinPaths = @(
    (Join-Path $sdkDir "platform-tools"),
    (Join-Path $sdkDir "emulator")
) | Where-Object { Test-Path $_ }

if ($sdkBinPaths.Count -gt 0) {
    $env:Path = ($sdkBinPaths -join ";") + ";" + $env:Path
}

$javaHome =
    First-ExistingPath @(
        $env:JAVA_HOME,
        "D:\Android\Android Studio\jbr",
        "D:\Android\Android Studio\jre",
        "$env:ProgramFiles\Android\Android Studio\jbr",
        "$env:ProgramFiles\Android\Android Studio\jre",
        "$env:USERPROFILE\.jdks\temurin-17*"
    )

if ($javaHome) {
    $env:JAVA_HOME = $javaHome
    $javaBin = Join-Path $javaHome "bin"
    if (Test-Path $javaBin) {
        $env:Path = "$javaBin;$env:Path"
    }
}

Write-Host "ANDROID_SDK_ROOT=$env:ANDROID_SDK_ROOT"
if ($env:JAVA_HOME) {
    Write-Host "JAVA_HOME=$env:JAVA_HOME"
} else {
    Write-Warning "JAVA_HOME not found. Install JDK 17 and set JAVA_HOME."
}
