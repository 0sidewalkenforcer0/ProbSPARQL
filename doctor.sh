#!/usr/bin/env bash

# ProbSPARQL Environment Doctor
# Checks local toolchain and Jena snapshot consistency.

set -u

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
POM_FILE="$ROOT_DIR/pom.xml"
REQUIRED_JAVA=21
REQUIRED_MAVEN="3.6.0"

PASS_COUNT=0
WARN_COUNT=0
FAIL_COUNT=0

color_reset="\033[0m"
color_green="\033[0;32m"
color_yellow="\033[1;33m"
color_red="\033[0;31m"
color_blue="\033[0;34m"

print_header() {
  echo "=============================================================="
  echo "ProbSPARQL Doctor"
  echo "=============================================================="
}

ok() {
  PASS_COUNT=$((PASS_COUNT + 1))
  echo -e "${color_green}[OK]${color_reset} $1"
}

warn() {
  WARN_COUNT=$((WARN_COUNT + 1))
  echo -e "${color_yellow}[WARN]${color_reset} $1"
}

fail() {
  FAIL_COUNT=$((FAIL_COUNT + 1))
  echo -e "${color_red}[FAIL]${color_reset} $1"
}

info() {
  echo -e "${color_blue}[INFO]${color_reset} $1"
}

version_ge() {
  # Returns 0 if $1 >= $2
  local v1="$1"
  local v2="$2"

  local IFS=.
  local i
  local a b
  read -r -a a <<< "$v1"
  read -r -a b <<< "$v2"

  local len=${#a[@]}
  if [ ${#b[@]} -gt "$len" ]; then
    len=${#b[@]}
  fi

  for ((i = 0; i < len; i++)); do
    local ai=${a[i]:-0}
    local bi=${b[i]:-0}
    if ((10#$ai > 10#$bi)); then
      return 0
    fi
    if ((10#$ai < 10#$bi)); then
      return 1
    fi
  done

  return 0
}

extract_xml_value() {
  local file="$1"
  local key="$2"
  sed -n "s:.*<$key>\(.*\)</$key>.*:\1:p" "$file" | head -n 1
}

check_java() {
  # Prefer $JAVA_HOME/bin/java over PATH java to respect explicit JDK selection
  local java_cmd="java"
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    java_cmd="$JAVA_HOME/bin/java"
  elif ! command -v java >/dev/null 2>&1; then
    fail "java not found in PATH and JAVA_HOME is not set"
    return
  fi

  local java_version_line
  java_version_line="$("$java_cmd" -version 2>&1 | head -n 1)"
  local java_version
  java_version="$("$java_cmd" -version 2>&1 | awk -F'"' '/version/ {print $2}' | head -n 1)"

  if [ -z "$java_version" ]; then
    fail "Unable to parse Java version from: $java_version_line"
    return
  fi

  local java_major
  java_major="$(echo "$java_version" | awk -F. '{if ($1=="1") print $2; else print $1}')"

  if [ "$java_major" -ge "$REQUIRED_JAVA" ]; then
    ok "Java version $java_version at $java_cmd (required >= $REQUIRED_JAVA)"
  else
    fail "Java version $java_version at $java_cmd is too old (required >= $REQUIRED_JAVA)"
  fi

  # Warn if PATH java differs from JAVA_HOME java
  if [ -n "${JAVA_HOME:-}" ]; then
    ok "JAVA_HOME is set: $JAVA_HOME"
    local path_java_version
    path_java_version="$(java -version 2>&1 | awk -F'"' '/version/ {print $2}' | head -n 1)"
    if [ "$path_java_version" != "$java_version" ]; then
      warn "PATH java ($path_java_version) differs from JAVA_HOME java ($java_version). Maven/IDE will use JAVA_HOME."
    fi
  else
    warn "JAVA_HOME is not set (recommended to avoid IDE/Maven mismatch)"
  fi
}

check_maven() {
  if ! command -v mvn >/dev/null 2>&1; then
    fail "mvn not found in PATH"
    return
  fi

  local maven_line
  maven_line="$(mvn -version 2>/dev/null | head -n 1)"
  local maven_version
  maven_version="$(echo "$maven_line" | sed -E 's/.* ([0-9]+\.[0-9]+\.[0-9]+).*/\1/')"

  if [ -z "$maven_version" ]; then
    warn "Unable to parse Maven version from: $maven_line"
    return
  fi

  if version_ge "$maven_version" "$REQUIRED_MAVEN"; then
    ok "Maven version $maven_version (required >= $REQUIRED_MAVEN)"
  else
    fail "Maven version $maven_version is too old (required >= $REQUIRED_MAVEN)"
  fi
}

check_pom_config() {
  if [ ! -f "$POM_FILE" ]; then
    fail "pom.xml not found at project root"
    return
  fi

  local jena_version
  jena_version="$(extract_xml_value "$POM_FILE" "jena.version")"
  local compiler_source
  compiler_source="$(extract_xml_value "$POM_FILE" "maven.compiler.source")"

  if [ -n "$jena_version" ]; then
    ok "pom.xml jena.version=$jena_version"
  else
    fail "Could not read <jena.version> from pom.xml"
  fi

  if [ -n "$compiler_source" ]; then
    ok "pom.xml maven.compiler.source=$compiler_source"
  else
    warn "Could not read <maven.compiler.source> from pom.xml"
  fi
}

check_jena_snapshot_consistency() {
  local jena_version
  jena_version="$(extract_xml_value "$POM_FILE" "jena.version")"

  if [ -z "$jena_version" ]; then
    fail "Cannot verify Jena snapshot consistency without jena.version"
    return
  fi

  local src_iface="$ROOT_DIR/jena/jena-core/src/main/java/org/apache/jena/datatypes/RDFDatatype.java"
  local jar_core="$HOME/.m2/repository/org/apache/jena/jena-core/$jena_version/jena-core-$jena_version.jar"
  local jar_arq="$HOME/.m2/repository/org/apache/jena/jena-arq/$jena_version/jena-arq-$jena_version.jar"

  if [ -f "$src_iface" ]; then
    if grep -q "canonicalizeLexicalForm(" "$src_iface"; then
      ok "Workspace Jena source has RDFDatatype.canonicalizeLexicalForm(String)"
    else
      warn "Workspace Jena source does not contain canonicalizeLexicalForm(String)"
    fi
  else
    warn "Workspace Jena source not found at jena/jena-core/.../RDFDatatype.java"
  fi

  if [ -f "$jar_core" ]; then
    ok "Local snapshot exists: $jar_core"
  else
    fail "Missing local jena-core snapshot: $jar_core"
  fi

  if [ -f "$jar_arq" ]; then
    ok "Local snapshot exists: $jar_arq"
  else
    fail "Missing local jena-arq snapshot: $jar_arq"
  fi

  if [ -f "$jar_core" ] && command -v javap >/dev/null 2>&1; then
    if javap -classpath "$jar_core" org.apache.jena.datatypes.RDFDatatype 2>/dev/null | grep -q "canonicalizeLexicalForm(java.lang.String)"; then
      ok "Installed jena-core snapshot API includes canonicalizeLexicalForm(String)"
    else
      fail "Installed jena-core snapshot API is missing canonicalizeLexicalForm(String)"
      warn "Likely source/jar mismatch. Reinstall modified Jena snapshots."
    fi
  elif [ -f "$jar_core" ]; then
    warn "javap not found; cannot inspect jena-core API methods"
  fi
}

print_fix_suggestions() {
  echo ""
  info "Recommended repair commands (copy/paste):"
  cat << 'EOF'
# 1) Use JDK 21 explicitly (example path on macOS)
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home

# 2) Rebuild and reinstall modified Jena snapshots
cd jena
mvn clean install -DskipTests
cd ..

# 3) Rebuild ProbSPARQL
mvn clean compile

# 4) If IDE still shows stale API errors, reload Maven project / restart Java language server
EOF
}

print_summary() {
  echo ""
  echo "=============================================================="
  echo "Summary"
  echo "=============================================================="
  echo "PASS: $PASS_COUNT"
  echo "WARN: $WARN_COUNT"
  echo "FAIL: $FAIL_COUNT"

  if [ "$FAIL_COUNT" -gt 0 ]; then
    echo ""
    echo -e "${color_red}Health check failed.${color_reset}"
    print_fix_suggestions
    exit 1
  fi

  if [ "$WARN_COUNT" -gt 0 ]; then
    echo ""
    echo -e "${color_yellow}Health check passed with warnings.${color_reset}"
    exit 0
  fi

  echo ""
  echo -e "${color_green}Health check passed.${color_reset}"
  exit 0
}

main() {
  print_header
  check_java
  check_maven
  check_pom_config
  check_jena_snapshot_consistency
  print_summary
}

main "$@"
