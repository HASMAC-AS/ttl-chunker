package no.hasmac.ttlchunker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class TurtleChunkerBenchmark {

	@Param({"0", "100", "1"})
	public int blankNodeEvery;

	@Param({"20000"})
	public int statements;

	@Param({"131072"})
	public long chunkSizeBytes;

	private Path workDir;
	private Path inputFile;
	private Path lastOutputDir;
	private int invocation;

	@Setup(Level.Trial)
	public void setUpTrial() throws IOException {
		workDir = Files.createTempDirectory("ttl-chunker-jmh-");
		inputFile = workDir.resolve("input.ttl");
		Files.writeString(inputFile, createInput(), StandardCharsets.UTF_8);
	}

	@TearDown(Level.Invocation)
	public void tearDownInvocation() throws IOException {
		if (lastOutputDir != null) {
			deleteRecursively(lastOutputDir);
			lastOutputDir = null;
		}
	}

	@TearDown(Level.Trial)
	public void tearDownTrial() throws IOException {
		if (workDir != null) {
			deleteRecursively(workDir);
			workDir = null;
		}
	}

	@Benchmark
	public int writeChunks() throws IOException {
		Path outputDir = workDir.resolve("chunks-" + invocation++);
		lastOutputDir = outputDir;
		return TurtleChunker.writeChunks(inputFile, chunkSizeBytes, outputDir, false);
	}

	private String createInput() {
		StringBuilder ttl = new StringBuilder(statements * 48);
		ttl.append("@prefix ex: <http://example.com/> .\n");
		for (int i = 0; i < statements; i++) {
			if (blankNodeEvery > 0 && i % blankNodeEvery == 0) {
				appendBlankNodeStatement(ttl, i);
			} else {
				appendRegularStatement(ttl, i);
			}
		}
		return ttl.toString();
	}

	private static void appendBlankNodeStatement(StringBuilder ttl, int statementIndex) {
		if ((statementIndex & 1) == 0) {
			ttl.append("_:b").append(statementIndex).append(" ex:p \"value-").append(statementIndex).append("\" .\n");
		} else {
			ttl.append("ex:s").append(statementIndex).append(" ex:p _:b").append(statementIndex).append(" .\n");
		}
	}

	private static void appendRegularStatement(StringBuilder ttl, int statementIndex) {
		ttl.append("ex:s").append(statementIndex).append(" ex:p \"value-").append(statementIndex).append("\" .\n");
	}

	private static void deleteRecursively(Path root) throws IOException {
		if (!Files.exists(root)) {
			return;
		}
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}
}
