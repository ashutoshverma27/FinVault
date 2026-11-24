#!/usr/bin/env sh
set -eu
GRADLE_VERSION="8.14.4"
BASE="${HOME}/.gradle/finvault"
GRADLE_HOME="${BASE}/gradle-${GRADLE_VERSION}"
if [ ! -x "${GRADLE_HOME}/bin/gradle" ]; then
  mkdir -p "${BASE}"
  ZIP="${BASE}/gradle-${GRADLE_VERSION}-bin.zip"
  echo "Downloading Gradle ${GRADLE_VERSION} for FinVault..."
  if command -v curl >/dev/null 2>&1; then
    curl -fL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o "${ZIP}"
  else
    wget "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -O "${ZIP}"
  fi
  unzip -q -o "${ZIP}" -d "${BASE}"
fi
exec "${GRADLE_HOME}/bin/gradle" "$@"
