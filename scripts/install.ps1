#Requires -Version 5.1
<#
.SYNOPSIS
    Kite CLI Installer for Windows
.DESCRIPTION
    Downloads and installs the Kite CLI tool.
    Usage: irm https://kitelang.cloud/install.ps1 | iex
.PARAMETER Version
    Specific version to install (default: latest)
.EXAMPLE
    irm https://kitelang.cloud/install.ps1 | iex
.EXAMPLE
    $env:KITE_VERSION = "1.0.0"; irm https://kitelang.cloud/install.ps1 | iex
#>

$ErrorActionPreference = "Stop"

# Configuration
$GitHubRepo = "kitecorp/kite-cli"
$KiteHome = "$env:USERPROFILE\.kite"
$JavaVersionRequired = 25

function Write-Info { param($Message) Write-Host "[INFO] " -ForegroundColor Blue -NoNewline; Write-Host $Message }
function Write-Success { param($Message) Write-Host "[OK] " -ForegroundColor Green -NoNewline; Write-Host $Message }
function Write-Warn { param($Message) Write-Host "[WARN] " -ForegroundColor Yellow -NoNewline; Write-Host $Message }
function Write-Err { param($Message) Write-Host "[ERROR] " -ForegroundColor Red -NoNewline; Write-Host $Message; exit 1 }

function Get-LatestVersion {
    try {
        $response = Invoke-RestMethod -Uri "https://api.github.com/repos/$GitHubRepo/releases/latest" -UseBasicParsing
        return $response.tag_name -replace '^v', ''
    } catch {
        return $null
    }
}

function Test-JavaInstalled {
    try {
        $javaVersion = & java -version 2>&1 | Select-Object -First 1
        if ($javaVersion -match '"(\d+)') {
            $version = [int]$Matches[1]
            return $version -ge $JavaVersionRequired
        }
    } catch {}
    return $false
}

function Install-Java {
    Write-Info "Java $JavaVersionRequired+ required but not found."

    # Try winget first (Windows 10 1709+ / Windows 11)
    if (Get-Command winget -ErrorAction SilentlyContinue) {
        Write-Info "Installing Java $JavaVersionRequired via winget..."
        try {
            winget install Microsoft.OpenJDK.$JavaVersionRequired --accept-source-agreements --accept-package-agreements
            # Refresh PATH in current session
            $env:Path = [Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [Environment]::GetEnvironmentVariable("Path", "User")
            Write-Success "Java installed"
            return
        } catch {
            Write-Warn "winget installation failed, trying alternative..."
        }
    }

    # Try Chocolatey
    if (Get-Command choco -ErrorAction SilentlyContinue) {
        Write-Info "Installing Java $JavaVersionRequired via Chocolatey..."
        try {
            choco install openjdk --version=$JavaVersionRequired -y
            # Refresh PATH in current session
            $env:Path = [Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [Environment]::GetEnvironmentVariable("Path", "User")
            Write-Success "Java installed"
            return
        } catch {
            Write-Warn "Chocolatey installation failed"
        }
    }

    # Manual instructions
    Write-Err @"
Could not install Java automatically.

Please install Java $JavaVersionRequired+ manually:
  1. Download from: https://adoptium.net/temurin/releases/?version=$JavaVersionRequired
  2. Or install via: winget install Microsoft.OpenJDK.$JavaVersionRequired
  3. Or install via: choco install openjdk --version=$JavaVersionRequired

Then run this installer again.
"@
}

function Install-Kite {
    param(
        [string]$Version,
        [string]$InstallDir,
        [bool]$UseJava
    )

    $arch = if ([Environment]::Is64BitOperatingSystem) { "amd64" } else { "x86" }

    if ($UseJava) {
        $filename = "kite-$Version-java.zip"
    } else {
        $filename = "kite-$Version-windows-$arch.zip"
    }

    $url = "https://github.com/$GitHubRepo/releases/download/v$Version/$filename"
    $tempDir = Join-Path $env:TEMP "kite-install-$(Get-Random)"
    $zipPath = Join-Path $tempDir $filename

    Write-Info "Downloading Kite $Version..."

    New-Item -ItemType Directory -Path $tempDir -Force | Out-Null

    try {
        Invoke-WebRequest -Uri $url -OutFile $zipPath -UseBasicParsing
    } catch {
        # Try without 'v' prefix
        $url = "https://github.com/$GitHubRepo/releases/download/$Version/$filename"
        try {
            Invoke-WebRequest -Uri $url -OutFile $zipPath -UseBasicParsing
        } catch {
            Write-Err "Failed to download: $url"
        }
    }

    Write-Info "Extracting to $InstallDir..."

    # Create install directory
    if (Test-Path $InstallDir) {
        Remove-Item -Recurse -Force $InstallDir
    }
    New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null

    # Extract
    $extractDir = Join-Path $tempDir "extract"
    Expand-Archive -Path $zipPath -DestinationPath $extractDir -Force

    # Find and copy contents
    $extracted = Get-ChildItem -Path $extractDir -Directory | Select-Object -First 1
    if ($extracted -and (Test-Path (Join-Path $extracted.FullName "bin"))) {
        Copy-Item -Path "$($extracted.FullName)\*" -Destination $InstallDir -Recurse -Force
    } else {
        Copy-Item -Path "$extractDir\*" -Destination $InstallDir -Recurse -Force
    }

    # Cleanup
    Remove-Item -Recurse -Force $tempDir

    Write-Success "Kite $Version installed to $InstallDir"
}

function Set-CurrentVersion {
    param([string]$VersionDir)

    $currentLink = Join-Path $KiteHome "current"

    # Remove existing junction/symlink
    if (Test-Path $currentLink) {
        Remove-Item -Force -Recurse $currentLink
    }

    # Create directory junction (works without admin rights)
    cmd /c mklink /J "$currentLink" "$VersionDir" | Out-Null

    $versionName = Split-Path $VersionDir -Leaf
    Write-Success "Linked current -> versions/$versionName"
}

function Add-ToPath {
    if ($env:KITE_NO_MODIFY_PATH -eq "1") {
        Write-Warn "Skipping PATH modification (KITE_NO_MODIFY_PATH=1)"
        return
    }

    $binDir = Join-Path $KiteHome "current\bin"
    $currentPath = [Environment]::GetEnvironmentVariable("Path", "User")

    if ($currentPath -like "*$KiteHome*") {
        Write-Info "PATH already contains Kite"
        return
    }

    Write-Info "Adding Kite to user PATH..."

    # Set KITE_HOME
    [Environment]::SetEnvironmentVariable("KITE_HOME", $KiteHome, "User")

    # Add to PATH
    $newPath = "$binDir;$currentPath"
    [Environment]::SetEnvironmentVariable("Path", $newPath, "User")

    # Update current session
    $env:KITE_HOME = $KiteHome
    $env:Path = "$binDir;$env:Path"

    Write-Success "Added to PATH"
}

function Main {
    Write-Host ""
    Write-Host "  _  ___ _       " -ForegroundColor Blue
    Write-Host " | |/ (_) |_ ___ " -ForegroundColor Blue
    Write-Host " | ' <| |  _/ -_)" -ForegroundColor Blue
    Write-Host " |_|\_\_|\__\___|" -ForegroundColor Blue
    Write-Host ""
    Write-Host "Kite CLI Installer"
    Write-Host "=================="
    Write-Host ""

    # Determine installation parameters
    $platform = "windows-amd64"
    Write-Info "Platform: $platform"

    $version = if ($env:KITE_VERSION) { $env:KITE_VERSION } else { Get-LatestVersion }
    if (-not $version) {
        Write-Err "Could not determine version. Set KITE_VERSION environment variable."
    }
    Write-Info "Version: $version"

    $versionDir = Join-Path $KiteHome "versions\$version"
    Write-Info "Install directory: $versionDir"

    # Try native binary first
    $useJava = $false
    $nativeUrl = "https://github.com/$GitHubRepo/releases/download/v$version/kite-$version-$platform.zip"

    try {
        $response = Invoke-WebRequest -Uri $nativeUrl -Method Head -UseBasicParsing -ErrorAction Stop
    } catch {
        # Also try without 'v' prefix
        $nativeUrl = "https://github.com/$GitHubRepo/releases/download/$version/kite-$version-$platform.zip"
        try {
            $response = Invoke-WebRequest -Uri $nativeUrl -Method Head -UseBasicParsing -ErrorAction Stop
        } catch {
            Write-Warn "Native binary not available, using Java distribution"
            $useJava = $true
        }
    }

    # If using Java distribution, ensure Java is installed
    if ($useJava) {
        if (-not (Test-JavaInstalled)) {
            Install-Java
        } else {
            Write-Success "Java $JavaVersionRequired+ detected"
        }
    }

    # Download and install
    Install-Kite -Version $version -InstallDir $versionDir -UseJava $useJava

    # Link current version
    Set-CurrentVersion -VersionDir $versionDir

    # Configure PATH
    Add-ToPath

    Write-Host ""
    Write-Success "Installation complete!"
    Write-Host ""
    Write-Host "Installed: ~\.kite\versions\$version"
    Write-Host "Current:   ~\.kite\current -> versions\$version"
    Write-Host ""
    Write-Host "To start using Kite, either:"
    Write-Host "  1. Restart your terminal, or"
    Write-Host "  2. Run: `$env:Path = [Environment]::GetEnvironmentVariable('Path', 'User')"
    Write-Host ""
    Write-Host "Then run:"
    Write-Host "  kite --version"
    Write-Host "  kite new my-project"
    Write-Host ""
}

Main
