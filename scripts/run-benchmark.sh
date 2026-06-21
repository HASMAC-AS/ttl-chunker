#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

mvn -q -Pbenchmark -DskipTests clean test-compile dependency:build-classpath \
	-Dmdep.outputFile=target/benchmark-classpath.txt \
	-Dmdep.includeScope=test

classpath="target/test-classes:target/classes:$(cat target/benchmark-classpath.txt)"

if [[ "$#" -eq 0 ]]; then
	set -- no.hasmac.ttlchunker.benchmark.TurtleChunkerBenchmark -wi 1 -i 3 -f 1
fi

exec java -cp "$classpath" org.openjdk.jmh.Main "$@"
