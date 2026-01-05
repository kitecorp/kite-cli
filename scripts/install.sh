#!/bin/bash
#
# Kite CLI Installer
# Usage: curl -fsSL https://kitelang.cloud/install.sh | bash
#
# Options (via environment variables):
#   KITE_VERSION    - Specific version to install (default: latest)
#   KITE_NO_MODIFY_PATH - Set to 1 to skip PATH modification
#
set -e

# Configuration
GITHUB_REPO="kitecorp/kite-cli"
KITE_HOME="$HOME/.kite"
JAVA_VERSION_REQUIRED=25

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

info() { echo -e "${BLUE}[INFO]${NC} $1"; }
success() { echo -e "${GREEN}[OK]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; exit 1; }

# Detect OS and architecture
detect_platform() {
    local os arch

    case "$(uname -s)" in
        Linux*)  os="linux" ;;
        Darwin*) os="osx" ;;
        *)       error "Unsupported OS: $(uname -s). Use Windows installer for Windows." ;;
    esac

    case "$(uname -m)" in
        x86_64|amd64)  arch="amd64" ;;
        arm64|aarch64) arch="arm64" ;;
        *)             error "Unsupported architecture: $(uname -m)" ;;
    esac

    echo "${os}-${arch}"
}

# Get latest version from GitHub
get_latest_version() {
    curl -fsSL "https://api.github.com/repos/${GITHUB_REPO}/releases/latest" | \
        grep '"tag_name":' | sed -E 's/.*"([^"]+)".*/\1/' | sed 's/^v//'
}

# Check if Java is installed and meets version requirement
check_java() {
    if ! command -v java &> /dev/null; then
        return 1
    fi

    local version
    version=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)

    if [[ "$version" -ge "$JAVA_VERSION_REQUIRED" ]]; then
        return 0
    fi
    return 1
}

# Install Java using SDKMAN
install_java() {
    info "Java $JAVA_VERSION_REQUIRED+ required but not found."

    if command -v sdk &> /dev/null; then
        info "Installing Java $JAVA_VERSION_REQUIRED via SDKMAN..."
        sdk install java "${JAVA_VERSION_REQUIRED}-open" || true
        sdk use java "${JAVA_VERSION_REQUIRED}-open"
    else
        info "Installing SDKMAN..."
        curl -s "https://get.sdkman.io?rcupdate=false" | bash

        # Source SDKMAN
        export SDKMAN_DIR="$HOME/.sdkman"
        # shellcheck source=/dev/null
        [[ -s "$SDKMAN_DIR/bin/sdkman-init.sh" ]] && source "$SDKMAN_DIR/bin/sdkman-init.sh"

        info "Installing Java $JAVA_VERSION_REQUIRED via SDKMAN..."
        sdk install java "${JAVA_VERSION_REQUIRED}-open"
    fi

    success "Java installed successfully"
}

# Download and extract Kite
download_kite() {
    local version="$1"
    local platform="$2"
    local install_dir="$3"
    local use_java="$4"

    local filename
    if [[ "$use_java" == "true" ]]; then
        filename="kite-${version}-java.zip"
    else
        filename="kite-${version}-${platform}.zip"
    fi

    local url="https://github.com/${GITHUB_REPO}/releases/download/v${version}/${filename}"
    local temp_dir
    temp_dir=$(mktemp -d)

    info "Downloading Kite ${version}..."
    if ! curl -fsSL "$url" -o "${temp_dir}/${filename}" 2>/dev/null; then
        # Try without 'v' prefix
        url="https://github.com/${GITHUB_REPO}/releases/download/${version}/${filename}"
        if ! curl -fsSL "$url" -o "${temp_dir}/${filename}" 2>/dev/null; then
            error "Failed to download Kite ${version}. Version may not exist.\nCheck available versions: https://github.com/${GITHUB_REPO}/releases"
        fi
    fi

    # Verify download succeeded (file exists and is not empty)
    if [[ ! -s "${temp_dir}/${filename}" ]]; then
        error "Downloaded file is empty or missing. Version ${version} may not exist."
    fi

    info "Extracting to ${install_dir}..."
    mkdir -p "$install_dir"
    unzip -q -o "${temp_dir}/${filename}" -d "${temp_dir}/extract"

    # Move contents (handle nested directory structure)
    local extracted_dir
    extracted_dir=$(find "${temp_dir}/extract" -mindepth 1 -maxdepth 1 -type d | head -1)

    if [[ -n "$extracted_dir" && -d "$extracted_dir/bin" ]]; then
        cp -r "$extracted_dir"/* "$install_dir/"
    else
        cp -r "${temp_dir}/extract"/* "$install_dir/"
    fi

    # Make binary executable
    chmod +x "$install_dir/bin/kite" 2>/dev/null || true

    # Cleanup
    rm -rf "$temp_dir"

    success "Kite ${version} installed to ${install_dir}"
}

# Create symlink to current version
link_current() {
    local version_dir="$1"
    local current_link="${KITE_HOME}/current"

    # Remove existing symlink
    rm -f "$current_link"

    # Create new symlink
    ln -s "$version_dir" "$current_link"

    success "Linked current -> versions/${version_dir##*/}"
}

# Add to PATH
configure_path() {
    local bin_dir="${KITE_HOME}/current/bin"

    if [[ "${KITE_NO_MODIFY_PATH:-0}" == "1" ]]; then
        warn "Skipping PATH modification (KITE_NO_MODIFY_PATH=1)"
        return
    fi

    # Detect shell config file
    local shell_rc
    case "$SHELL" in
        */zsh)  shell_rc="$HOME/.zshrc" ;;
        */bash)
            if [[ -f "$HOME/.bash_profile" ]]; then
                shell_rc="$HOME/.bash_profile"
            else
                shell_rc="$HOME/.bashrc"
            fi
            ;;
        */fish) shell_rc="$HOME/.config/fish/config.fish" ;;
        *)      shell_rc="$HOME/.profile" ;;
    esac

    # Check if already in PATH config
    if grep -q "KITE_HOME" "$shell_rc" 2>/dev/null; then
        info "PATH already configured in $shell_rc"
        return
    fi

    info "Adding Kite to PATH in $shell_rc..."

    if [[ "$SHELL" == */fish ]]; then
        mkdir -p "$(dirname "$shell_rc")"
        cat >> "$shell_rc" << EOF

# Kite CLI
set -gx KITE_HOME "$KITE_HOME"
fish_add_path "\$KITE_HOME/current/bin"
EOF
    else
        cat >> "$shell_rc" << EOF

# Kite CLI
export KITE_HOME="$KITE_HOME"
export PATH="\$KITE_HOME/current/bin:\$PATH"
EOF
    fi

    success "Added to $shell_rc"
}

# Main installation
main() {
    echo ""
    echo -e "${BLUE}  _  ___ _       ${NC}"
    echo -e "${BLUE} | |/ (_) |_ ___ ${NC}"
    echo -e "${BLUE} | ' <| |  _/ -_)${NC}"
    echo -e "${BLUE} |_|\\_\\_|\\__\\___|${NC}"
    echo ""
    echo "Kite CLI Installer"
    echo "=================="
    echo ""

    # Determine installation parameters
    local platform
    platform=$(detect_platform)
    info "Detected platform: $platform"

    local version="${KITE_VERSION:-$(get_latest_version)}"
    if [[ -z "$version" ]]; then
        error "Could not determine version. Set KITE_VERSION environment variable."
    fi
    # Normalize version: strip 'v' prefix if present
    version="${version#v}"
    info "Version: $version"

    local version_dir="${KITE_HOME}/versions/${version}"
    info "Install directory: $version_dir"

    # Try native binary first, fall back to Java distribution
    local use_java="false"

    # Check if native binary exists for this platform
    local native_url="https://github.com/${GITHUB_REPO}/releases/download/v${version}/kite-${version}-${platform}.zip"
    if ! curl -fsSL --head "$native_url" &>/dev/null; then
        # Also try without 'v' prefix
        native_url="https://github.com/${GITHUB_REPO}/releases/download/${version}/kite-${version}-${platform}.zip"
        if ! curl -fsSL --head "$native_url" &>/dev/null; then
            warn "Native binary not available for $platform, using Java distribution"
            use_java="true"
        fi
    fi

    # If using Java distribution, ensure Java is installed
    if [[ "$use_java" == "true" ]]; then
        if ! check_java; then
            install_java
        else
            success "Java $JAVA_VERSION_REQUIRED+ detected"
        fi
    fi

    # Download and install
    download_kite "$version" "$platform" "$version_dir" "$use_java"

    # Link current version
    link_current "$version_dir"

    # Configure PATH
    configure_path

    echo ""
    success "Installation complete!"
    echo ""
    echo "Installed: ~/.kite/versions/$version"
    echo "Current:   ~/.kite/current -> versions/$version"
    echo ""
    echo "To start using Kite, either:"
    echo "  1. Restart your terminal, or"
    echo "  2. Run: source ~/.zshrc  (or ~/.bashrc)"
    echo ""
    echo "Then run:"
    echo "  kite --version"
    echo "  kite new my-project"
    echo ""
    echo "To upgrade or manage versions later:"
    echo "  kite upgrade              # Upgrade to latest version"
    echo "  kite version list         # List installed versions"
    echo "  kite version install 1.0  # Install specific version"
    echo "  kite version use 1.0      # Switch to installed version"
    echo ""
}

main "$@"
