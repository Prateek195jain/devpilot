#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p target/core-tests
java CompileCore.java
java -cp target/core-tests CoreSmoke
