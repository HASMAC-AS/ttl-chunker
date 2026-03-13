# ttl-chunker

Splits a Turtle file into smaller Turtle files.

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

Optional output dir:

```bash
java -jar target/ttl-chunker-1.0-SNAPSHOT.jar input.ttl 128MB ./chunks
```

Chunk size accepts:

- raw bytes: `50000000`
- binary-ish suffixes: `64KB`, `128MB`, `2GB`

Behavior:

- splits on Turtle statement/directive boundaries
- keeps chunk size approximate, not exact
- prepends every chunk with all `@prefix` / `@base` directives seen so far
- default output dir: `<input-name>-chunks/`

## Project Structure

```
ttl-chunker/
├── pom.xml                                          # Maven build descriptor
└── src/
    ├── main/java/no/hasmac/ttlchunker/
    │   ├── TurtleBlockReader.java                   # Streaming Turtle block reader
    │   └── TurtleChunker.java                       # CLI + chunk writer
    └── test/java/no/hasmac/ttlchunker/
        └── TurtleChunkerTest.java                   # Regression coverage
```
