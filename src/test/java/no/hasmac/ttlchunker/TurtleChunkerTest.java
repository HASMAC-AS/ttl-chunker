package no.hasmac.ttlchunker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
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
}
