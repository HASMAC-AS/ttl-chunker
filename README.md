# ttl-chunker

Splits Turtle and TriG files into smaller valid chunk files.

## Prerequisites

- [Java 21](https://adoptium.net/) (or later)
- [Apache Maven 3.6+](https://maven.apache.org/download.cgi)

Verify your installations:

```bash
java -version
mvn -version
```

## Building

Compile the project and package it into a JAR file:

```bash
mvn package
```

Jar: `target/ttl-chunker-1.0-SNAPSHOT.jar`

## Running

```bash
java -jar target/ttl-chunker-1.0-SNAPSHOT.jar input.ttl 128MB
```

TriG input is supported too:

```bash
java -jar target/ttl-chunker-1.0-SNAPSHOT.jar input.trig 128MB
```

Optional output dir:

```bash
java -jar target/ttl-chunker-1.0-SNAPSHOT.jar input.ttl 128MB ./chunks
```

Chunk size accepts:

- raw bytes: `50000000`
- binary-ish suffixes: `64KB`, `128MB`, `2GB`

Behavior:

- splits on Turtle/TriG statement/directive boundaries
- rewraps split TriG graph statements so each chunk remains valid TriG
- supports default graph blocks, graph-label blocks, and `GRAPH graph-label` blocks
- keeps chunk size approximate, not exact
- prepends every chunk with all `@prefix` / `@base` directives seen so far
- default output dir: `<input-name>-chunks/`
- RDF-star/TriG-star is not supported

## Benchmarking

Run a quick local smoke benchmark:

```bash
./scripts/run-benchmark.sh no.hasmac.ttlchunker.benchmark.TurtleChunkerBenchmark -wi 1 -i 3 -f 1
```

Useful parameters:

```bash
./scripts/run-benchmark.sh no.hasmac.ttlchunker.benchmark.TurtleChunkerBenchmark \
  -p syntax=ttl,trigLabeledGraph,trigMixedGraphs \
  -p blankNodeEvery=0,100,1 \
  -p statements=20000 \
  -p chunkSizeBytes=131072
```

## Project Structure

```
ttl-chunker/
├── pom.xml                                          # Maven build descriptor
└── src/
    ├── main/java/no/hasmac/ttlchunker/
    │   ├── TurtleBlockReader.java                   # Streaming Turtle/TriG block reader
    │   ├── ChunkSink.java                           # Chunk writer + graph wrapping
    │   └── TurtleChunker.java                       # CLI + public facade
    └── test/java/no/hasmac/ttlchunker/
        ├── benchmark/
        │   └── TurtleChunkerBenchmark.java          # JMH benchmark fixtures
        └── TurtleChunkerTest.java                   # Regression coverage
```
