# ttl-chunker

Chunks a turtle file into smaller files.

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

The compiled JAR will be placed in the `target/` directory.

## Running

### Using the Maven exec plugin

```bash
mvn exec:java -Dexec.mainClass="no.hasmac.ttlchunker.HelloWorld"
```

### Using the packaged JAR

After building, run the JAR directly:

```bash
java -jar target/ttl-chunker-1.0-SNAPSHOT.jar
```

Expected output:

```
Hello, World!
```

## Project structure

```
ttl-chunker/
├── pom.xml                                          # Maven build descriptor
└── src/
    └── main/
        └── java/
            └── no/hasmac/ttlchunker/
                └── HelloWorld.java                  # Main entry point
```
