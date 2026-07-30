#!/usr/bin/env bash

set -e

OS="$(uname -s)"
ARCH="$(uname -m)"

echo "========================================"
echo "    TRON Java Dependencies Installer"
echo "========================================"
echo ""
echo ">>> Environment Detection"
if [[ "$OS" == "Darwin" ]]; then
    echo "  OS: macOS $OS"
else
    echo "  OS: $OS"
fi
echo "  Architecture: $ARCH"

# Validate OS and architecture support first
if [[ "$OS" != "Darwin" && "$OS" != "Linux" ]]; then
    echo "Error: Unsupported OS $OS"
    exit 1
elif [[ "$OS" == "Darwin" ]]; then
    if [[ "$ARCH" != "x86_64" && "$ARCH" != "arm64" ]]; then
        echo "Error: Unsupported architecture for macOS: $ARCH"
        exit 1
    fi
else
    if [[ "$ARCH" != "x86_64" && "$ARCH" != "aarch64" && "$ARCH" != "arm64" ]]; then
        echo "Error: Unsupported architecture for Linux: $ARCH"
        exit 1
    fi
fi

echo ""
echo ">>> Tested platforms:"
echo "    - macOS x86_64 (JDK 8 or newer)"
echo "    - macOS arm64 (JDK 17 or newer)"
echo "    - Linux x86_64 (generic, including Ubuntu) (JDK 8 or newer)"
echo "    - Linux arm64/aarch64 (generic, including Ubuntu) (JDK 17 or newer)"
echo "    Note: Other platforms may require manual installation if errors occur"
echo ""
echo ">>> This script will install the following components if not already installed:"
if [[ "$OS" == "Darwin" ]]; then
    echo "  1. Homebrew to download and install JDK (macOS only)"
    echo "  2. Git for cloning Github repository"
else
    echo "  1. Git for cloning Github repository"
fi
if [[ "$OS" == "Darwin" ]]; then
    if [[ "$ARCH" == "x86_64" ]]; then
        echo "  3. OpenJDK 8 (default installer choice for x86_64)"
    else
        echo "  3. OpenJDK 17 (default installer choice for arm64)"
    fi
else
    if [[ "$ARCH" == "x86_64" ]]; then
        echo "  2. OpenJDK 8 (default installer choice for x86_64)"
    else
        echo "  2. OpenJDK 17 (default installer choice for arm64/aarch64)"
    fi
fi
echo ""

# Function to ask for user confirmation
ask_confirmation() {
    while true; do
        read -p "Do you want to continue? (y/N): " yn
        case $yn in
            [Yy]* ) return 0;;
            [Nn]* | "" ) echo "Installation cancelled."; exit 0;;
            * ) echo "Please answer yes (y) or no (n).";;
        esac
    done
}

# Return success when javac ships with the installation the given java
# launcher belongs to.
has_javac() {
    local java_cmd="$1"
    local java_major="$2"

    # An explicit JAVA_HOME must be a complete JDK on its own: that directory
    # is what gradlew hands to the Gradle daemon.
    if [[ -n "${JAVA_HOME:-}" ]]; then
        [[ -x "$JAVA_HOME/bin/javac" ]]
        return $?
    fi

    local java_home
    java_home=$("$java_cmd" -XshowSettings:properties -version 2>&1 \
        | awk -F'= ' '/java\.home/ {print $2}')
    if [[ -z "$java_home" ]]; then
        return 1
    fi
    if [[ -x "$java_home/bin/javac" ]]; then
        return 0
    fi
    # On JDK 8 java.home is the jre subdirectory of the JDK.
    [[ "$java_major" == "8" && -x "$java_home/../bin/javac" ]]
}

# Return success when the installed JDK meets the architecture's minimum version.
check_supported_java_version() {
    local minimum_major="$1"

    # Check the JVM gradlew would pick: JAVA_HOME wins over PATH. Callers
    # report on the very same launcher through SELECTED_JAVA_CMD.
    local java_cmd
    SELECTED_JAVA_CMD=""
    if [[ -n "${JAVA_HOME:-}" ]]; then
        java_cmd="$JAVA_HOME/bin/java"
        if [[ ! -x "$java_cmd" ]]; then
            echo "    JAVA_HOME is set to an invalid directory: $JAVA_HOME"
            return 1
        fi
    elif command -v java &> /dev/null; then
        java_cmd="$(command -v java)"
    else
        return 2
    fi
    SELECTED_JAVA_CMD="$java_cmd"

    local java_version
    local java_major
    java_version=$("$java_cmd" -version 2>&1 | head -n 1 | cut -d'"' -f2)
    echo "    Current Java version: $java_version ($java_cmd)"

    if [[ "$java_version" =~ ^1\.([0-9]+) ]]; then
        java_major="${BASH_REMATCH[1]}"
    else
        java_major="${java_version%%.*}"
    fi

    if [[ "$java_major" =~ ^[0-9]+$ ]] && (( java_major >= minimum_major )); then
        # A JRE-only installation passes the version check but cannot compile.
        if ! has_javac "$java_cmd" "$java_major"; then
            echo "    Found java $java_version but no javac in the same"
            echo "    installation: building java-tron requires a full JDK."
            return 1
        fi
        echo "    JDK $java_major is supported on $ARCH (minimum JDK $minimum_major)."
        return 0
    fi

    echo "    Unsupported Java version detected: $java_version"
    echo "    Minimum version for $ARCH: JDK $minimum_major"
    return 1
}

# Function to ask for JDK installation confirmation
ask_jdk_confirmation() {
    local current_version="$1"
    local recommended_version="$2"
    local arch="$3"
    
    echo ""
    echo "Unsupported JDK Version Detected!"
    echo "    Current version: $current_version"
    echo "    Current installation path: ${SELECTED_JAVA_CMD:-Not found}"
    if [[ -n "${JAVA_HOME:-}" ]]; then
        echo "    Current JAVA_HOME: $JAVA_HOME"
    fi
    echo "    Recommended installation for $arch: $recommended_version"
    echo "    This script will install $recommended_version alongside your existing installation."
    echo "    Your current Java installation will not be removed."
    echo ""
    
    while true; do
        read -p "Do you want to install $recommended_version? (y/N): " yn
        case $yn in
            [Yy]* ) return 0;;
            [Nn]* | "" ) echo "JDK installation cancelled. Exiting."; exit 0;;
            * ) echo "Please answer yes (y) or no (n).";;
        esac
    done
}

# First, check and install Git (needed for cloning repository)
echo ">>> Checking Git installation..."
if ! command -v git &> /dev/null; then
    echo "    Git is not installed."
    while true; do
        read -p "Do you want to install Git (required for cloning the java-tron repository)? (y/N): " yn
        case $yn in
            [Yy]* ) 
                echo ">>> Installing Git..."
                INSTALL_GIT=true
                break;;
            [Nn]* | "" ) 
                echo "Git installation cancelled. You'll need Git to clone the java-tron repository."
                echo "You can install Git manually later and then clone the repository."
                INSTALL_GIT=false
                break;;
            * ) echo "Please answer yes (y) or no (n).";;
        esac
    done
else
    echo "Git is already installed: $(git --version)"
    INSTALL_GIT=false
fi

# Determine the minimum and default JDK for the current architecture.
if [[ "$OS" == "Darwin" ]]; then
    if [[ "$ARCH" == "x86_64" ]]; then
        recommended_jdk="JDK 8"
        minimum_java_major=8
    elif [[ "$ARCH" == "arm64" ]]; then
        recommended_jdk="JDK 17"
        minimum_java_major=17
    fi
elif [[ "$OS" == "Linux" ]]; then
    if [[ "$ARCH" == "x86_64" ]]; then
        recommended_jdk="JDK 8"
        minimum_java_major=8
    elif [[ "$ARCH" == "aarch64" ]] || [[ "$ARCH" == "arm64" ]]; then
        recommended_jdk="JDK 17"
        minimum_java_major=17
    fi
fi

echo ""
echo ">>> Checking existing Java installation..."
set +e  # Temporarily disable exit on error
check_supported_java_version "$minimum_java_major"
java_status=$?
set -e  # Re-enable exit on error

# Keep every existing JDK that meets the architecture's minimum version.
if [[ $java_status -eq 0 ]]; then
    echo "    You can skip the Java installation part."
    echo ""
    if [[ "$INSTALL_GIT" == "false" ]]; then
        echo "Both Git and Java JDK are ready for TRON development!"
        echo ""
        exit 0
    else
        echo ">>> Proceeding with Git installation only..."
        SKIP_JAVA_INSTALL=true
    fi
elif [[ $java_status -eq 1 ]]; then
    # An older or unrecognized JDK is installed; offer a supported default.
    current_version="Not found"
    if [[ -n "${SELECTED_JAVA_CMD:-}" ]]; then
        current_version=$("$SELECTED_JAVA_CMD" -version 2>&1 | head -n 1 | cut -d'"' -f2)
    fi
    ask_jdk_confirmation "$current_version" "$recommended_jdk" "$ARCH"
    SKIP_JAVA_INSTALL=false
else
    # No Java installation found, ask for general confirmation
    echo ""
    echo "No Java installation detected!"
    echo "    This script will install $recommended_jdk as the default for $ARCH."
    echo ""
    ask_confirmation
    SKIP_JAVA_INSTALL=false
fi

# Function to show permanent Java configuration instructions
show_permanent_java_config() {
    local jdk_version="$1"
    local os_type="$2"
    local java_home="$3"
    local java_bin_path="$4"
    
    echo ""
    echo "    To make JDK $jdk_version permanent:"
    if [[ "$os_type" == "Darwin" ]]; then
        echo "        # Add to ~/.zshrc or ~/.bash_profile:"
        echo "        echo 'export JAVA_HOME=\"$java_home\"' >> ~/.zshrc"
        echo "        echo 'export PATH=\"\$JAVA_HOME/bin:\$PATH\"' >> ~/.zshrc"
        echo "        # Then run below command:"
        echo "        source ~/.zshrc"
        echo ""
        echo "        # Or use jenv for Java version management:"
        echo "        brew install jenv"
        echo "        jenv add $java_home"
    elif [[ "$os_type" == "Linux" ]]; then
        echo "        # Method 1: Add to ~/.bashrc:"
        echo "        echo 'export JAVA_HOME=\"$java_home\"' >> ~/.bashrc"
        echo "        echo 'export PATH=\"\$JAVA_HOME/bin:\$PATH\"' >> ~/.bashrc"
        echo "        source ~/.bashrc"
        echo ""
        echo "        # Method 2: Use update-alternatives (recommended):"
        if [[ "$PKG_MANAGER" == "apt-get" ]]; then
            echo "        sudo update-alternatives --install /usr/bin/java java $java_bin_path/java 1"
            echo "        sudo update-alternatives --install /usr/bin/javac javac $java_bin_path/javac 1"
            echo "        sudo update-alternatives --config java"
        else
            echo "        sudo alternatives --install /usr/bin/java java $java_bin_path/java 1"
            echo "        sudo alternatives --install /usr/bin/javac javac $java_bin_path/javac 1"
            echo "        sudo alternatives --config java"
        fi
    fi
    echo ""
}

# Function to show Java environment application instructions
show_java_env_instructions() {
    local java_home="$1"
    local java_bin_path="$2"
    
    echo ""
    echo "    ✓ Java environment has been applied to this script session."
    echo "    To apply Java environment to your current terminal session:"
    echo "        source ./tron_java_env.sh"
    echo ""
    echo "    Or run these commands directly:"
    echo "        export JAVA_HOME=\"$java_home\""
    echo "        export PATH=\"$java_bin_path:\$PATH\""
    echo ""
    echo "    Note: You may need to open a new terminal or run 'source ./tron_java_env.sh'"
    echo "    if 'java -version' doesn't work immediately after this script completes."
}

# Function to get Java paths based on OS and architecture
get_java_paths() {
    local jdk_version="$1"
    local os_type="$2"
    local arch="$3"
    local java_home=""
    
    if [[ "$os_type" == "Darwin" ]]; then
        # macOS paths - try to detect actual Homebrew installation
        if [[ "$jdk_version" == "8" ]]; then
            # Try multiple possible paths for JDK 8
            for path in "/usr/local/opt/openjdk@8" "/opt/homebrew/opt/openjdk@8"; do
                if [[ -d "$path" ]]; then
                    java_home="$path"
                    break
                fi
            done
            # If not found, use brew --prefix to get the correct path
            if [[ -z "$java_home" ]] && command -v brew &> /dev/null; then
                local brew_prefix=$(brew --prefix openjdk@8 2>/dev/null || echo "")
                if [[ -n "$brew_prefix" && -d "$brew_prefix" ]]; then
                    java_home="$brew_prefix"
                fi
            fi
        elif [[ "$jdk_version" == "17" ]]; then
            # Try multiple possible paths for JDK 17
            for path in "/opt/homebrew/opt/openjdk@17" "/usr/local/opt/openjdk@17"; do
                if [[ -d "$path" ]]; then
                    java_home="$path"
                    break
                fi
            done
            # If not found, use brew --prefix to get the correct path
            if [[ -z "$java_home" ]] && command -v brew &> /dev/null; then
                local brew_prefix=$(brew --prefix openjdk@17 2>/dev/null || echo "")
                if [[ -n "$brew_prefix" && -d "$brew_prefix" ]]; then
                    java_home="$brew_prefix"
                fi
            fi
        fi
    elif [[ "$os_type" == "Linux" ]]; then
        # Linux paths - provide generic path for manual configuration
        java_home="/usr/lib/jvm/java-$jdk_version-openjdk"
    fi
    
    echo "$java_home"
}

# Unified Java environment configuration function
configure_java_environment() {
    local jdk_version="$1"
    local os_type="$2"
    local arch="$3"
    local java_home=""
    local java_bin_path=""
    
    echo ""
    echo ">>> Configuring Java environment for JDK $jdk_version..."
    
    # Ask user for confirmation before changing environment
    echo ""
    echo "This will modify your Java environment settings:"
    echo "    - Set JAVA_HOME to the new JDK $jdk_version installation"
    echo "    - Update PATH to include the new Java binaries"
    echo "    - Create a script (tron_java_env.sh) for easy environment setup"
    echo ""
    
    while true; do
        read -p "Do you want to configure the Java environment for JDK $jdk_version? (y/N): " yn
        case $yn in
            [Yy]* ) 
                echo ">>> Proceeding with Java environment configuration..."
                break;;
            [Nn]* | "" ) 
                echo "Java environment configuration skipped."
                echo "You may need to manually set JAVA_HOME and PATH for JDK $jdk_version"
                echo ""
                echo "Manual configuration commands:"
                
                # Get the expected Java path
                local expected_java_home=$(get_java_paths "$jdk_version" "$os_type" "$arch")
                local expected_java_bin_path="$expected_java_home/bin"
                echo "    export JAVA_HOME=\"$expected_java_home\""
                echo "    export PATH=\"\$JAVA_HOME/bin:\$PATH\""
                
                if [[ "$os_type" == "Linux" ]]; then
                    echo ""
                    echo "Note: Actual path may vary depending on your distribution."
                    echo "Common paths include:"
                    echo "    /usr/lib/jvm/java-$jdk_version-openjdk-amd64 (Ubuntu/Debian)"
                    echo "    /usr/lib/jvm/java-1.$jdk_version.0-openjdk (RHEL/CentOS)"
                fi
                
                # Create tron_java_env.sh even when user skips configuration
                echo ""
                echo ">>> Creating tron_java_env.sh for manual use later..."
                local env_script="./tron_java_env.sh"
                cat > "$env_script" << EOF
#!/bin/bash
# TRON Java Environment Configuration
# Generated by install_dependencies.sh on $(date)

export JAVA_HOME="$expected_java_home"
export PATH="$expected_java_bin_path:\$PATH"

echo "Java environment configured:"
echo "   JAVA_HOME: \$JAVA_HOME"
echo "   Java version: \$(java -version 2>&1 | head -n 1)"
EOF
                chmod +x "$env_script"
                echo "    ✓ Created $env_script"
                
                # Show the same application instructions as automatic configuration
                show_java_env_instructions "$expected_java_home" "$expected_java_bin_path"
                
                # Show permanent configuration instructions
                show_permanent_java_config "$jdk_version" "$os_type" "$expected_java_home" "$expected_java_bin_path"
                
                echo ""
                return 1;;
            * ) echo "Please answer yes (y) or no (n).";;
        esac
    done
    
    # Determine Java paths based on OS and architecture
    if [[ "$os_type" == "Darwin" ]]; then
        # Use the helper function for macOS
        java_home=$(get_java_paths "$jdk_version" "$os_type" "$arch")
        java_bin_path="$java_home/bin"
        
        # Debug output for macOS
        echo "    Detected Java path: $java_home"
        if [[ ! -d "$java_home" ]]; then
            echo "    Warning: Java home directory not found at: $java_home"
            echo "    Attempting to find JDK installation..."
            
            # Try to find the installation using brew
            if command -v brew &> /dev/null; then
                local brew_list=$(brew list --formula | grep "openjdk@$jdk_version" || echo "")
                if [[ -n "$brew_list" ]]; then
                    echo "    Found Homebrew package: $brew_list"
                    local brew_prefix=$(brew --prefix openjdk@$jdk_version 2>/dev/null || echo "")
                    if [[ -n "$brew_prefix" && -d "$brew_prefix" ]]; then
                        java_home="$brew_prefix"
                        java_bin_path="$java_home/bin"
                        echo "    Updated Java path to: $java_home"
                    fi
                else
                    echo "    Error: openjdk@$jdk_version not found in Homebrew packages"
                    echo "    Try running: brew list | grep openjdk"
                    return 1
                fi
            fi
        fi
    elif [[ "$os_type" == "Linux" ]]; then
        # Linux paths - try to find the actual installation
        if [[ "$jdk_version" == "8" ]]; then
            if [[ "$PKG_MANAGER" == "apt-get" ]]; then
                if [[ "$arch" == "aarch64" ]] || [[ "$arch" == "arm64" ]]; then
                    java_home="/usr/lib/jvm/java-8-openjdk-arm64"
                else
                    java_home="/usr/lib/jvm/java-8-openjdk-amd64"
                fi
            else
                # RHEL/CentOS/Amazon Linux - try multiple possible paths
                for path in "/usr/lib/jvm/java-1.8.0-amazon-corretto" "/usr/lib/jvm/java-1.8.0-openjdk"; do
                    if [[ -d "$path" ]]; then
                        java_home="$path"
                        break
                    fi
                done
            fi
        elif [[ "$jdk_version" == "17" ]]; then
            if [[ "$PKG_MANAGER" == "apt-get" ]]; then
                if [[ "$arch" == "aarch64" ]] || [[ "$arch" == "arm64" ]]; then
                    java_home="/usr/lib/jvm/java-17-openjdk-arm64"
                else
                    java_home="/usr/lib/jvm/java-17-openjdk-amd64"
                fi
            else
                # RHEL/CentOS/Amazon Linux - try multiple possible paths
                for path in "/usr/lib/jvm/java-17-amazon-corretto" "/usr/lib/jvm/java-17-openjdk"; do
                    if [[ -d "$path" ]]; then
                        java_home="$path"
                        break
                    fi
                done
            fi
        fi
        java_bin_path="$java_home/bin"
    fi
    
    # Set environment variables for current session
    if [[ -d "$java_home" ]]; then
        export JAVA_HOME="$java_home"
        export PATH="$java_bin_path:$PATH"
        echo "    JAVA_HOME set to: $JAVA_HOME"
        echo "    PATH updated to include: $java_bin_path"
        echo "    Environment temporarily configured for JDK $jdk_version"
        
        # Create a source script for the user's current shell
        local env_script="./tron_java_env.sh"
        cat > "$env_script" << EOF
#!/bin/bash
# TRON Java Environment Configuration
# Generated by install_dependencies.sh on $(date)

export JAVA_HOME="$java_home"
export PATH="$java_bin_path:\$PATH"

echo "Java environment configured:"
echo "   JAVA_HOME: \$JAVA_HOME"
echo "   Java version: \$(java -version 2>&1 | head -n 1)"
EOF
        chmod +x "$env_script"
        echo ""
        echo "    ✓ Created $env_script"
        
        echo ""
        echo "    Applying Java environment to current shell session..."
        # Source the environment script to apply it immediately
        source "$env_script"
        
        show_java_env_instructions "$java_home" "$java_bin_path"
        
    else
        echo "    Could not find Java installation at expected path: $java_home"
        echo "    You may need to set JAVA_HOME manually"
        return 1
    fi
    
    # Provide OS-specific permanent configuration instructions
    show_permanent_java_config "$jdk_version" "$os_type" "$java_home" "$java_bin_path"
    
    return 0
}

echo "----------------------------------------"

install_macos() {
    if ! command -v brew &> /dev/null; then
        echo ">>> Homebrew not found."
        echo "    Homebrew is required to install Java on macOS."
        echo ""
        while true; do
            read -p "Do you want to install Homebrew? (y/N): " yn
            case $yn in
                [Yy]* ) 
                    echo ">>> Installing Homebrew..."
                    /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
                    break;;
                [Nn]* | "" ) 
                    echo "Homebrew installation cancelled."
                    echo "Cannot proceed with Java installation without Homebrew on macOS."
                    echo "Please install Homebrew manually or use alternative Java installation methods."
                    exit 1;;
                * ) echo "Please answer yes (y) or no (n).";;
            esac
        done
        
        # Add Homebrew to PATH for the current session (Apple Silicon vs Intel)
        if [[ "$ARCH" == "arm64" ]]; then
            eval "$(/opt/homebrew/bin/brew shellenv)"
        else
            eval "$(/usr/local/bin/brew shellenv)"
        fi
    else
        echo ">>> Homebrew is already installed."
    fi

    echo ">>> Updating Homebrew..."
    brew update

    # Install Git if needed
    if [[ "$INSTALL_GIT" == "true" ]]; then
        echo ">>> Installing Git..."
        brew install git
        echo "    Git installed successfully: $(git --version)"
    fi

    # Skip Java installation if flag is set
    if [[ "$SKIP_JAVA_INSTALL" == "true" ]]; then
        echo ">>> Skipping Java installation (correct version already detected)."
        return 0
    fi

    if [[ "$ARCH" == "x86_64" ]]; then
        echo ">>> Architecture is x86_64. Checking for a supported JDK..."
        set +e  # Temporarily disable exit on error
        check_supported_java_version "$minimum_java_major"
        local java_status=$?
        set -e  # Re-enable exit on error
        
        if [[ $java_status -eq 0 ]]; then
            echo ">>> A supported JDK is already installed. Skipping installation."
        else
            echo ">>> Installing JDK 8 alongside any existing Java installation..."
            if brew install openjdk@8; then
                echo ">>> JDK 8 installation completed successfully."
                
                # Use unified Java environment configuration
                if configure_java_environment "8" "Darwin" "$ARCH"; then
                    echo "Environment has been updated! Java 8 is now configured."
                else
                    echo "Java 8 installed but environment not configured. You may need to set JAVA_HOME manually."
                fi
            else
                echo "Error: Failed to install JDK 8 via Homebrew."
                echo "Please try installing manually with: brew install openjdk@8"
                exit 1
            fi
        fi
        
    elif [[ "$ARCH" == "arm64" ]]; then
        echo ">>> Architecture is arm64. Checking for a supported JDK..."
        set +e  # Temporarily disable exit on error
        check_supported_java_version "$minimum_java_major"
        local java_status=$?
        set -e  # Re-enable exit on error
        
        if [[ $java_status -eq 0 ]]; then
            echo ">>> A supported JDK is already installed. Skipping installation."
        else
            echo ">>> Installing JDK 17 alongside any existing Java installation..."
            if brew install openjdk@17; then
                echo ">>> JDK 17 installation completed successfully."

                # Use unified Java environment configuration
                if configure_java_environment "17" "Darwin" "$ARCH"; then
                    echo "Environment has been updated! Java 17 is now configured."
                else
                    echo "Java 17 installed but environment not configured. You may need to set JAVA_HOME manually."
                fi
            else
                echo "Error: Failed to install JDK 17 via Homebrew."
                echo "Please try installing manually with: brew install openjdk@17"
                exit 1
            fi
        fi

    else
        echo "Error: Unsupported architecture for macOS script: $ARCH"
        exit 1
    fi
}

install_linux() {
    if command -v dnf &> /dev/null; then
        PKG_MANAGER="dnf"
        INSTALL_CMD="sudo dnf install -y"
        UPDATE_CMD="sudo dnf check-update"
    elif command -v yum &> /dev/null; then
        PKG_MANAGER="yum"
        INSTALL_CMD="sudo yum install -y"
        UPDATE_CMD="sudo yum check-update"
    elif command -v apt-get &> /dev/null; then
        PKG_MANAGER="apt-get"
        INSTALL_CMD="sudo apt-get install -y"
        UPDATE_CMD="sudo apt-get update"
    else
        echo "Error: Unsupported package manager. Only apt-get (Debian/Ubuntu) and yum/dnf (RHEL/CentOS/Amazon Linux) are currently supported."
        exit 1
    fi

    echo ">>> Updating package index ($PKG_MANAGER)..."
    $UPDATE_CMD || true

    # Install Git if needed
    if [[ "$INSTALL_GIT" == "true" ]]; then
        echo ">>> Installing Git..."
        $INSTALL_CMD git
        echo "    Git installed successfully: $(git --version)"
    fi

    # Skip Java installation if flag is set
    if [[ "$SKIP_JAVA_INSTALL" == "true" ]]; then
        echo ">>> Skipping Java installation (correct version already detected)."
        return 0
    fi

    install_first_available() {
        local target_version="$1"
        shift
        local installed_package=""
        
        for pkg in "$@"; do
            echo "    Attempting to install: $pkg"
            if $INSTALL_CMD "$pkg"; then
                installed_package="$pkg"
                echo "    Successfully installed: $pkg"
                break
            else
                echo "    Failed to install: $pkg"
            fi
        done
        
        if [[ -n "$installed_package" ]]; then
            # Verify what version was actually installed
            echo "    Verifying installed Java version..."
            if command -v java &> /dev/null; then
                local actual_version=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2)
                echo "    Installed Java version: $actual_version"
                
                # Check if the installed version matches what we expected
                if [[ "$target_version" == "8" ]]; then
                    if [[ "$actual_version" =~ ^1\.8\. ]]; then
                        echo "    ✓ JDK 8 installed successfully"
                        return 0
                    else
                        echo "    ✗ Expected JDK 8 but got: $actual_version"
                        echo "    This may happen if JDK 8 is not available in your distribution"
                        return 2
                    fi
                elif [[ "$target_version" == "17" ]]; then
                    if [[ "$actual_version" =~ ^17\. ]]; then
                        echo "    ✓ JDK 17 installed successfully"
                        return 0
                    else
                        echo "    ✗ Expected JDK 17 but got: $actual_version"
                        return 2
                    fi
                fi
            else
                echo "    ✗ Java command not found after installation"
                return 1
            fi
        else
            return 1
        fi
    }

    if [[ "$ARCH" == "x86_64" ]]; then
        echo ">>> Architecture is x86_64. Checking for a supported JDK..."
        set +e  # Temporarily disable exit on error
        check_supported_java_version "$minimum_java_major"
        local java_status=$?
        set -e  # Re-enable exit on error
        
        if [[ $java_status -eq 0 ]]; then
            echo ">>> A supported JDK is already installed. Skipping installation."
        else
            echo ">>> Installing JDK 8 alongside any existing Java installation..."
            if [[ "$PKG_MANAGER" == "apt-get" ]]; then
                if install_first_available "8" openjdk-8-jdk; then
                    install_result=0
                else
                    install_result=$?
                fi
            else
                if install_first_available "8" java-1.8.0-amazon-corretto-devel java-1.8.0-openjdk-devel; then
                    install_result=0
                else
                    install_result=$?
                fi
            fi
            
            if [[ $install_result -eq 0 ]]; then
                # Use unified Java environment configuration
                if configure_java_environment "8" "Linux" "$ARCH"; then
                    echo "Environment has been updated! Java 8 is now configured."
                else
                    echo "Java 8 installed but environment not configured. You may need to set JAVA_HOME manually."
                fi
            elif [[ $install_result -eq 2 ]]; then
                # JDK 8 package is installed but default version is different
                # Need to switch to JDK 8 using update-alternatives
                local actual_version=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2)
                echo ">>> JDK 8 package is installed, but system default is: $actual_version"
                echo ">>> Switching system default to JDK 8 using update-alternatives..."
                
                # Try to switch to JDK 8
                if [[ "$PKG_MANAGER" == "apt-get" ]]; then
                    local jdk8_path="/usr/lib/jvm/java-8-openjdk-amd64"
                    if [[ -d "$jdk8_path" ]]; then
                        echo "    Found JDK 8 at: $jdk8_path"
                        # Set JDK 8 as default using update-alternatives
                        sudo update-alternatives --set java "$jdk8_path/jre/bin/java" 2>/dev/null || \
                        sudo update-alternatives --set java "$jdk8_path/bin/java" 2>/dev/null || \
                        echo "    Note: Could not auto-switch. Please run: sudo update-alternatives --config java"
                        
                        sudo update-alternatives --set javac "$jdk8_path/bin/javac" 2>/dev/null || \
                        echo "    Note: Could not auto-switch javac. Please run: sudo update-alternatives --config javac"
                        
                        # Verify the switch
                        local new_version=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2)
                        if [[ "$new_version" =~ ^1\.8\. ]]; then
                            echo "    ✓ Successfully switched to JDK 8: $new_version"
                            # Now configure environment for JDK 8
                            if configure_java_environment "8" "Linux" "$ARCH"; then
                                echo "Environment has been updated! Java 8 is now configured."
                            else
                                echo "Java 8 is active but environment not configured. You may need to set JAVA_HOME manually."
                            fi
                        else
                            echo "    ✗ Auto-switch failed. Current version: $new_version"
                            echo "    Please manually switch to JDK 8:"
                            echo "        sudo update-alternatives --config java"
                            echo "        sudo update-alternatives --config javac"
                            echo "    Then configure environment for JDK 8"
                        fi
                    else
                        echo "    ✗ JDK 8 directory not found at expected location: $jdk8_path"
                        echo "    Please manually locate and configure JDK 8"
                    fi
                else
                    # For yum/dnf systems
                    echo "    Please manually switch to JDK 8:"
                    echo "        sudo alternatives --config java"
                    echo "        sudo alternatives --config javac"
                    echo ""
                    echo "    After switching, you can configure the environment."
                    
                    # Still create tron_java_env.sh for manual use
                    if configure_java_environment "8" "Linux" "$ARCH"; then
                        echo "Environment configuration completed for JDK 8."
                    else
                        echo "tron_java_env.sh has been created for manual use."
                        echo "After switching to JDK 8, run: source ./tron_java_env.sh"
                    fi
                fi
            else
                echo "Error: Unable to install any JDK on $PKG_MANAGER"
                exit 1
            fi
        fi
        
    elif [[ "$ARCH" == "aarch64" ]] || [[ "$ARCH" == "arm64" ]]; then
        echo ">>> Architecture is arm64/aarch64. Checking for a supported JDK..."
        set +e  # Temporarily disable exit on error
        check_supported_java_version "$minimum_java_major"
        local java_status=$?
        set -e  # Re-enable exit on error

        if [[ $java_status -eq 0 ]]; then
            echo ">>> A supported JDK is already installed. Skipping installation."
        else
            echo ">>> Installing JDK 17 alongside any existing Java installation..."
            if [[ "$PKG_MANAGER" == "apt-get" ]]; then
                if install_first_available "17" openjdk-17-jdk; then
                    install_result=0
                else
                    install_result=$?
                fi
            else
                if install_first_available "17" java-17-amazon-corretto-devel java-17-openjdk-devel; then
                    install_result=0
                else
                    install_result=$?
                fi
            fi
            
            if [[ $install_result -eq 0 ]]; then
                # Use unified Java environment configuration
                if configure_java_environment "17" "Linux" "$ARCH"; then
                    echo "Environment has been updated! Java 17 is now configured."
                else
                    echo "Java 17 installed but environment not configured. You may need to set JAVA_HOME manually."
                fi
            elif [[ $install_result -eq 2 ]]; then
                # JDK 17 package is installed but default version is different
                # Need to switch to JDK 17 using update-alternatives
                local actual_version=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2)
                echo ">>> JDK 17 package is installed, but system default is: $actual_version"
                echo ">>> Switching system default to JDK 17 using update-alternatives..."
                
                # Try to switch to JDK 17
                if [[ "$PKG_MANAGER" == "apt-get" ]]; then
                    local jdk17_path=""
                    if [[ "$ARCH" == "aarch64" ]] || [[ "$ARCH" == "arm64" ]]; then
                        jdk17_path="/usr/lib/jvm/java-17-openjdk-arm64"
                    else
                        jdk17_path="/usr/lib/jvm/java-17-openjdk-amd64"
                    fi
                    
                    if [[ -d "$jdk17_path" ]]; then
                        echo "    Found JDK 17 at: $jdk17_path"
                        # Set JDK 17 as default using update-alternatives
                        sudo update-alternatives --set java "$jdk17_path/bin/java" 2>/dev/null || \
                        echo "    Note: Could not auto-switch. Please run: sudo update-alternatives --config java"
                        
                        sudo update-alternatives --set javac "$jdk17_path/bin/javac" 2>/dev/null || \
                        echo "    Note: Could not auto-switch javac. Please run: sudo update-alternatives --config javac"
                        
                        # Verify the switch
                        local new_version=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2)
                        if [[ "$new_version" =~ ^17\. ]]; then
                            echo "    ✓ Successfully switched to JDK 17: $new_version"
                            # Now configure environment for JDK 17
                            if configure_java_environment "17" "Linux" "$ARCH"; then
                                echo "Environment has been updated! Java 17 is now configured."
                            else
                                echo "Java 17 is active but environment not configured. You may need to set JAVA_HOME manually."
                            fi
                        else
                            echo "    ✗ Auto-switch failed. Current version: $new_version"
                            echo "    Please manually switch to JDK 17:"
                            echo "        sudo update-alternatives --config java"
                            echo "        sudo update-alternatives --config javac"
                            echo "    Then configure environment for JDK 17"
                        fi
                    else
                        echo "    ✗ JDK 17 directory not found at expected location: $jdk17_path"
                        echo "    Please manually locate and configure JDK 17"
                    fi
                else
                    # For yum/dnf systems
                    echo "    Please manually switch to JDK 17:"
                    echo "        sudo alternatives --config java"
                    echo "        sudo alternatives --config javac"
                    echo ""
                    echo "    After switching, you can configure the environment."
                    
                    # Still create tron_java_env.sh for manual use
                    if configure_java_environment "17" "Linux" "$ARCH"; then
                        echo "Environment configuration completed for JDK 17."
                    else
                        echo "tron_java_env.sh has been created for manual use."
                        echo "After switching to JDK 17, run: source ./tron_java_env.sh"
                    fi
                fi
            else
                echo "Error: Unable to install any JDK on $PKG_MANAGER"
                exit 1
            fi
        fi
        
    else
        echo "Error: Unsupported architecture for Linux script: $ARCH"
        exit 1
    fi
}

if [[ "$OS" == "Darwin" ]]; then
    install_macos
elif [[ "$OS" == "Linux" ]]; then
    install_linux
else
    echo "Error: Unsupported Operating System: $OS"
    exit 1
fi

echo "----------------------------------------"

# Re-run the same validator the build relies on, so the script never reports
# success for an environment gradlew cannot build with.
echo ">>> Verifying the Java environment..."
set +e  # Temporarily disable exit on error
check_supported_java_version "$minimum_java_major"
final_java_status=$?
set -e  # Re-enable exit on error

if [[ $final_java_status -ne 0 ]]; then
    echo ""
    echo "✗ The current environment cannot build java-tron."
    echo ""
    echo ">>> Troubleshooting:"
    echo "  - Load the JDK installed above: source ./tron_java_env.sh"
    echo "  - Or point JAVA_HOME at a JDK $minimum_java_major or newer installation."
    echo "  - For permanent configuration, follow the instructions shown above."
    echo ""
    exit 1
fi

echo ""
echo "Installation completed successfully!"
echo ""
echo ">>> Verification Commands:"
echo "  git --version"
echo "  java -version"
echo ""