#!/usr/bin/env sh
set -eu
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
cd "$(dirname "$0")"
[ -f build/com/cncd/ch04/test/ProtocolIntegrationTest.class ] || ./compile.sh
java -cp build com.cncd.ch04.test.ProtocolIntegrationTest
