package no.hasmac.ttlchunker.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import no.hasmac.ttlchunker.TurtleChunker;
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
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.CommandLineOptions;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class TurtleChunkerBenchmark {

	@Param({"ttl", "trigLabeledGraph", "trigMixedGraphs"})
	public String syntax;

	@Param({"0", "100", "1"})
	public int blankNodeEvery;

	@Param({"1000000"})
	public int statements;

	@Param({"10485760"})
	public long chunkSizeBytes;

	private Path workDir;
	private Path inputFile;
	private Path lastOutputDir;
	private int invocation;

	public static void main(String[] args) throws Exception {
		Options options = new OptionsBuilder()
				.include(TurtleChunkerBenchmark.class.getName())
				.parent(new CommandLineOptions(args))
				.forks(0)
				.build();
		new Runner(options).run();
	}

	@Setup(Level.Trial)
	public void setUpTrial() throws IOException {
		workDir = Files.createTempDirectory("ttl-chunker-jmh-");
		inputFile = workDir.resolve(syntax.equals("ttl") ? "input.ttl" : "input.trig");
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
		if (syntax.equals("trigLabeledGraph")) {
			return createTrigLabeledGraphInput();
		}
		if (syntax.equals("trigMixedGraphs")) {
			return createTrigMixedGraphsInput();
		}
		return createTtlInput();
	}

	private String createTtlInput() {
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

	private String createTrigLabeledGraphInput() {
		StringBuilder trig = new StringBuilder(statements * 56);
		trig.append("@prefix ex: <http://example.com/> .\n");
		trig.append("ex:g {\n");
		for (int i = 0; i < statements; i++) {
			if (blankNodeEvery > 0 && i % blankNodeEvery == 0) {
				appendBlankNodeStatement(trig, i);
			} else {
				appendRegularStatement(trig, i);
			}
		}
		trig.append("}\n");
		return trig.toString();
	}

	private String createTrigMixedGraphsInput() {
		StringBuilder trig = new StringBuilder(statements * 64);
		trig.append("@prefix ex: <http://example.com/> .\n");
		for (int i = 0; i < statements; i++) {
			if (i % 4 == 0) {
				trig.append("ex:g").append(i % 8).append(" {\n");
				appendBenchmarkStatement(trig, i);
				trig.append("}\n");
			} else if (i % 4 == 1) {
				trig.append("GRAPH ex:g").append(i % 8).append(" {\n");
				appendBenchmarkStatement(trig, i);
				trig.append("} .\n");
			} else if (i % 4 == 2) {
				trig.append("{\n");
				appendBenchmarkStatement(trig, i);
				trig.append("}\n");
			} else {
				appendBenchmarkStatement(trig, i);
			}
		}
		return trig.toString();
	}

	private void appendBenchmarkStatement(StringBuilder ttl, int statementIndex) {
		if (blankNodeEvery > 0 && statementIndex % blankNodeEvery == 0) {
			appendBlankNodeStatement(ttl, statementIndex);
		} else {
			appendRegularStatement(ttl, statementIndex);
		}
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
