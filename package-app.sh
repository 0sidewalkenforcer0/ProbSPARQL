#!/bin/bash
# =============================================================================
#  ProbSPARQL Desktop App Packager
#
#  Builds a self-contained native desktop application using jpackage.
#  The resulting app bundles a JRE – users do NOT need Java installed.
#
#  Prerequisites (on the build machine only):
#    - JDK 21+ with jpackage  (comes with OpenJDK 14+)
#    - Maven 3.6+
#    - macOS: Xcode command-line tools  (for .app/.dmg)
#    - Windows: WiX Toolset            (for .msi)
#    - Linux: fakeroot + dpkg or rpm   (for .deb/.rpm)
#
#  Usage:
#    ./package-app.sh              # detect OS, build default format
#    ./package-app.sh --dmg        # macOS disk image
#    ./package-app.sh --pkg        # macOS installer package
#    ./package-app.sh --msi        # Windows installer
#    ./package-app.sh --exe        # Windows EXE installer
#    ./package-app.sh --deb        # Debian/Ubuntu package
#    ./package-app.sh --rpm        # RPM package
# =============================================================================

set -e

# ---------- Configuration ----------------------------------------------------
APP_NAME="ProbSPARQL"
APP_VERSION="1.0.0"
APP_VENDOR="ProbSPARQL Team"
APP_DESCRIPTION="Probabilistic SPARQL Knowledge Graph Query Engine"
MAIN_CLASS="org.apache.jena.probsparql.app.AppLauncher"
FAT_JAR="target/probsparql-app.jar"
OUTPUT_DIR="dist"

# Detect Java home (prefer JAVA_HOME)
if [[ -n "$JAVA_HOME" ]]; then
    JAVA_BIN="$JAVA_HOME/bin"
else
    JAVA_BIN="$(dirname "$(command -v java)")"
fi
JPACKAGE="$JAVA_BIN/jpackage"

# ---------- Helpers ----------------------------------------------------------
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*" >&2; }

# ---------- Detect OS & default format ---------------------------------------
OS="$(uname -s)"
case "$OS" in
    Darwin) DEFAULT_TYPE="dmg" ;;
    Linux)  DEFAULT_TYPE="deb" ;;
    MINGW*|MSYS*|CYGWIN*) DEFAULT_TYPE="msi" ;;
    *) warn "Unknown OS: $OS, using 'app-image'"; DEFAULT_TYPE="app-image" ;;
esac

# Parse optional flag override
TYPE="$DEFAULT_TYPE"
for arg in "$@"; do
    case "$arg" in
        --dmg)       TYPE="dmg"       ;;
        --pkg)       TYPE="pkg"       ;;
        --msi)       TYPE="msi"       ;;
        --exe)       TYPE="exe"       ;;
        --deb)       TYPE="deb"       ;;
        --rpm)       TYPE="rpm"       ;;
        --app-image) TYPE="app-image" ;;
    esac
done

echo ""
echo "╔════════════════════════════════════════════════════════════════╗"
echo "║  ProbSPARQL Desktop App Packager                               ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""
info "Target format : $TYPE"
info "Output dir    : $OUTPUT_DIR/"
echo ""

# ---------- Preflight checks -------------------------------------------------
if [[ ! -x "$JPACKAGE" ]]; then
    error "jpackage not found at: $JPACKAGE"
    error "jpackage requires JDK 14+. Set JAVA_HOME to a JDK 21 installation."
    exit 1
fi

JPACKAGE_VERSION=$("$JPACKAGE" --version 2>&1 | head -1)
info "jpackage      : $JPACKAGE_VERSION"

# ---------- Build fat JAR ----------------------------------------------------
info "Building fat JAR via Maven (this may take a minute)..."
mvn -q package -DskipTests

if [[ ! -f "$FAT_JAR" ]]; then
    error "Fat JAR not found after build: $FAT_JAR"
    exit 1
fi
info "Fat JAR built : $FAT_JAR ($(du -sh "$FAT_JAR" | cut -f1))"

# ---------- Prepare resources dir (icon etc.) --------------------------------
RESOURCES_DIR="src/main/app-resources"
mkdir -p "$RESOURCES_DIR"

# Create a simple placeholder icon if none exists
if [[ "$OS" == "Darwin" && ! -f "$RESOURCES_DIR/ProbSPARQL.icns" ]]; then
    warn "No icon found at $RESOURCES_DIR/ProbSPARQL.icns — app will use default Java icon."
    warn "To add a custom icon: place ProbSPARQL.icns in $RESOURCES_DIR/"
fi
if [[ "$OS" == "Linux" && ! -f "$RESOURCES_DIR/ProbSPARQL.png" ]]; then
    warn "No icon found at $RESOURCES_DIR/ProbSPARQL.png — app will use default Java icon."
fi

# ---------- Run jpackage -----------------------------------------------------
mkdir -p "$OUTPUT_DIR"

info "Running jpackage..."
echo ""

JPACKAGE_ARGS=(
    --type "$TYPE"
    --name "$APP_NAME"
    --app-version "$APP_VERSION"
    --vendor "$APP_VENDOR"
    --description "$APP_DESCRIPTION"
    --input "$(dirname "$FAT_JAR")"
    --main-jar "$(basename "$FAT_JAR")"
    --main-class "$MAIN_CLASS"
    --dest "$OUTPUT_DIR"
    --java-options "-Xms256m"
    --java-options "-Xmx1g"
    --java-options "-XX:+UseG1GC"
    --java-options "-XX:MaxGCPauseMillis=100"
    --java-options "-XX:+UseStringDeduplication"
    --java-options "-Djava.awt.headless=false"
)

# macOS-specific
if [[ "$OS" == "Darwin" ]]; then
    JPACKAGE_ARGS+=(
        --mac-package-name "$APP_NAME"
    )
    if [[ -f "$RESOURCES_DIR/ProbSPARQL.icns" ]]; then
        JPACKAGE_ARGS+=(--icon "$RESOURCES_DIR/ProbSPARQL.icns")
    fi
fi

# Linux-specific
if [[ "$OS" == "Linux" ]]; then
    JPACKAGE_ARGS+=(
        --linux-package-name "probsparql"
        --linux-app-category "Science"
    )
    if [[ -f "$RESOURCES_DIR/ProbSPARQL.png" ]]; then
        JPACKAGE_ARGS+=(--icon "$RESOURCES_DIR/ProbSPARQL.png")
    fi
fi

"$JPACKAGE" "${JPACKAGE_ARGS[@]}"

# ---------- Done -------------------------------------------------------------
echo ""
echo "╔════════════════════════════════════════════════════════════════╗"
echo "║  Packaging complete!                                           ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""
info "Output:"
ls -lh "$OUTPUT_DIR"/
echo ""
info "What to do next:"
case "$TYPE" in
    dmg)
        echo "  1. Open  $OUTPUT_DIR/ProbSPARQL-${APP_VERSION}.dmg"
        echo "  2. Drag ProbSPARQL to /Applications"
        echo "  3. Double-click ProbSPARQL — browser opens automatically at http://localhost:3030/"
        ;;
    pkg)
        echo "  1. Double-click $OUTPUT_DIR/ProbSPARQL-${APP_VERSION}.pkg to install"
        echo "  2. Launch from /Applications or Spotlight"
        ;;
    msi|exe)
        echo "  1. Run the installer in $OUTPUT_DIR/"
        echo "  2. Launch ProbSPARQL from the Start Menu"
        ;;
    deb|rpm)
        echo "  1. Install: sudo dpkg -i $OUTPUT_DIR/probsparql_${APP_VERSION}*.deb"
        echo "     or:      sudo rpm -i $OUTPUT_DIR/probsparql-${APP_VERSION}*.rpm"
        echo "  2. Run:     probsparql"
        ;;
    app-image)
        echo "  1. App image is in $OUTPUT_DIR/$APP_NAME/"
        echo "  2. Run:  $OUTPUT_DIR/$APP_NAME/bin/$APP_NAME"
        ;;
esac
echo ""
