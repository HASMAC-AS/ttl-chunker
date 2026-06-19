package no.hasmac.ttlchunker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TurtleChunkerTest {

	@Test
	void parseChunkSizeSupportsExpectedFormats() {
		assertEquals(50000000L, TurtleChunker.parseChunkSize("50000000"));
		assertEquals(64L * 1024L, TurtleChunker.parseChunkSize("64KB"));
		assertEquals(128L * 1024L * 1024L, TurtleChunker.parseChunkSize("128mb"));
		assertEquals(2L * 1024L * 1024L * 1024L, TurtleChunker.parseChunkSize("2GB"));
	}

	@Test
	void parseChunkSizeRejectsInvalidValues() {
		assertThrows(IllegalArgumentException.class, () -> TurtleChunker.parseChunkSize(""));
		assertThrows(IllegalArgumentException.class, () -> TurtleChunker.parseChunkSize("0"));
		assertThrows(IllegalArgumentException.class, () -> TurtleChunker.parseChunkSize("12XB"));
	}

	@Test
	void writeChunksRotatesFilesAndPrependsPrefixes(@TempDir Path tempDir) throws IOException {
		Path inputFile = tempDir.resolve("input.ttl");
		String input = """
				@prefix ex: <http://example.com/> .
				ex:s1 ex:p "value-1111111111" .
				ex:s2 ex:p "value-2222222222" .
				ex:s3 ex:p "value-3333333333" .
				ex:s4 ex:p "value-4444444444" .
				""";
		Files.writeString(inputFile, input, StandardCharsets.UTF_8);

		Path outputDir = tempDir.resolve("chunks");
		int chunkCount = TurtleChunker.writeChunks(inputFile, 80, outputDir);
		assertTrue(chunkCount > 1, "Expected more than one output chunk");

		List<Path> chunkFiles;
		try (Stream<Path> paths = Files.list(outputDir)) {
			chunkFiles = paths
					.filter(Files::isRegularFile)
					.sorted(Comparator.comparing(path -> path.getFileName().toString()))
					.collect(Collectors.toList());
		}

		assertEquals(chunkCount, chunkFiles.size());
		for (Path chunkFile : chunkFiles) {
			String content = Files.readString(chunkFile, StandardCharsets.UTF_8);
			assertTrue(content.startsWith("@prefix ex: <http://example.com/> ."),
					"Chunk missing prefix header: " + chunkFile.getFileName());
			assertFalse(content.isBlank(), "Chunk file should not be blank: " + chunkFile.getFileName());
		}
	}

	@Test
	void writeChunksKeepsBlankNodeIdStatementsInOneChunk(@TempDir Path tempDir) throws IOException {
		Path inputFile = tempDir.resolve("input.ttl");
		String input = """
				@prefix ex: <http://example.com/> .
				_:shared ex:first "one" .
				ex:regular1 ex:p "value-1111111111" .
				_:shared ex:second "two" .
				ex:regular2 ex:p "value-2222222222" .
				ex:regular3 ex:link _:other .
				""";
		Files.writeString(inputFile, input, StandardCharsets.UTF_8);

		Path outputDir = tempDir.resolve("chunks");
		int chunkCount = TurtleChunker.writeChunks(inputFile, 1, outputDir);
		assertTrue(chunkCount > 1, "Expected chunk size to force multiple output chunks");

		List<Path> chunkFiles;
		try (Stream<Path> paths = Files.list(outputDir)) {
			chunkFiles = paths
					.filter(Files::isRegularFile)
					.sorted(Comparator.comparing(path -> path.getFileName().toString()))
					.collect(Collectors.toList());
		}

		List<Path> blankNodeChunks = chunkFiles.stream()
				.filter(path -> {
					try {
						return Files.readString(path, StandardCharsets.UTF_8).contains("_:shared");
					} catch (IOException e) {
						throw new AssertionError(e);
					}
				})
				.collect(Collectors.toList());
		assertEquals(1, blankNodeChunks.size(), "Blank node ID statements should share a chunk");

		String blankNodeChunk = Files.readString(blankNodeChunks.getFirst(), StandardCharsets.UTF_8);
		assertTrue(blankNodeChunk.contains("_:shared ex:first"));
		assertTrue(blankNodeChunk.contains("_:shared ex:second"));
		assertTrue(blankNodeChunk.contains("ex:regular3 ex:link _:other"));
	}

	@Test
	void writeChunksCanRunWithoutStatusOutput(@TempDir Path tempDir) throws IOException {
		Path inputFile = tempDir.resolve("input.ttl");
		String input = """
				@prefix ex: <http://example.com/> .
				ex:s1 ex:p "value" .
				""";
		Files.writeString(inputFile, input, StandardCharsets.UTF_8);

		ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		PrintStream originalOut = System.out;
		try (PrintStream replacement = new PrintStream(stdout, true, StandardCharsets.UTF_8)) {
			System.setOut(replacement);
			int chunkCount = TurtleChunker.writeChunks(inputFile, 80, tempDir.resolve("chunks"), false);
			assertEquals(1, chunkCount);
		} finally {
			System.setOut(originalOut);
		}

		assertEquals("", stdout.toString(StandardCharsets.UTF_8));
	}

	@Test
	void writeChunksPrintsTimingLineForEachChunk(@TempDir Path tempDir) throws IOException {
		Path inputFile = tempDir.resolve("input.ttl");
		String input = """
				@prefix ex: <http://example.com/> .
				ex:s1 ex:p "value-1111111111" .
				ex:s2 ex:p "value-2222222222" .
				""";
		Files.writeString(inputFile, input, StandardCharsets.UTF_8);

		ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		SequenceClock clock = new SequenceClock(1000, 2500, 3000, 3750);
		try (PrintStream replacement = new PrintStream(stdout, true, StandardCharsets.UTF_8)) {
			int chunkCount = TurtleChunker.writeChunks(inputFile, 1, tempDir.resolve("chunks"), true,
					replacement, clock::nextMillis);
			assertEquals(2, chunkCount);
		}

		String output = stdout.toString(StandardCharsets.UTF_8);
		assertTrue(output.contains("Wrote chunk chunk-00001.ttl in 1.500 seconds\n"));
		assertTrue(output.contains("Wrote chunk chunk-00002.ttl in 0.750 seconds\n"));
	}

	private static final class SequenceClock {
		private final long[] millis;
		private int index;

		private SequenceClock(long... millis) {
			this.millis = millis;
		}

		private long nextMillis() {
			return millis[index++];
		}
	}
}
