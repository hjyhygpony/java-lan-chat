#!/usr/bin/env sh
set -eu
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
cd "$(dirname "$0")"
rm -rf build
mkdir -p build
find src -name '*.java' -print > sources.txt
javac -encoding UTF-8 -d build @sources.txt
rm -f sources.txt
echo "编译成功。"
