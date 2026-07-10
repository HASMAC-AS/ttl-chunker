package no.hasmac.ttlchunker;

import static no.hasmac.ttlchunker.RdfTestSupport.assertChunkUnionIsomorphic;
import static no.hasmac.ttlchunker.RdfTestSupport.assertEveryChunkParses;
import static no.hasmac.ttlchunker.RdfTestSupport.listRegularFiles;
import static no.hasmac.ttlchunker.RdfTestSupport.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Chunker-specific invariants that are independent of any particular syntax feature: size
 * overshoot, rotation math, blank-node chunk routing, degenerate inputs, input validation, and a
 * read-buffer/chunk-size grid over the rich fixtures.
 */
class TurtleChunkerInvariantTest {

	@Test
	void singleStatementLargerThanChunkSize(@TempDir Path tempDir) throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				ex:s ex:p "%s" .
				""".formatted("x".repeat(65_536));
		Path inputFile = writeInput(tempDir, "input.ttl", input);

		Path outputDir = tempDir.resolve("chunks");
		int chunkCount = TurtleChunker.writeChunks(inputFile, 16, outputDir, false);

		assertEquals(1, chunkCount, "A statement larger than the chunk size still becomes one chunk");
		assertTrue(Files.size(listRegularFiles(outputDir).getFirst()) > 16,
				"Chunk size is approximate; a single statement may overshoot");
		assertEveryChunkParses(outputDir, RDFFormat.TURTLE);
		assertChunkUnionIsomorphic(inputFile, outputDir, RDFFormat.TURTLE);

		Path smallReadOutputDir = tempDir.resolve("chunks-small-read");
		int smallReadChunkCount = TurtleChunker.writeChunks(inputFile, 16, smallReadOutputDir, false,
				System.out, System::currentTimeMillis, 7);
		assertEquals(1, smallReadChunkCount);
		assertEveryChunkParses(smallReadOutputDir, RDFFormat.TURTLE);
		assertChunkUnionIsomorphic(inputFile, smallReadOutputDir, RDFFormat.TURTLE);
	}

	@Test
	void oversizedStatementFollowedBySmallOnesResumesRotation(@TempDir Path tempDir) throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				ex:big ex:p "%s" .
				ex:s1 ex:p "one" .
				ex:s2 ex:p "two" .
				ex:s3 ex:p "three" .
				""".formatted("x".repeat(65_536));
		Path inputFile = writeInput(tempDir, "input.ttl", input);

		Path outputDir = tempDir.resolve("chunks");
		int chunkCount = TurtleChunker.writeChunks(inputFile, 1, outputDir, false);

		assertEquals(4, chunkCount, "Rotation must resume after an oversized statement");
		assertEquals(4, listRegularFiles(outputDir).size());
		assertEveryChunkParses(outputDir, RDFFormat.TURTLE);
		assertChunkUnionIsomorphic(inputFile, outputDir, RDFFormat.TURTLE);
	}

	@Test
	void chunkCountMatchesReturnValueAndRotationMath(@TempDir Path tempDir) throws IOException {
		StringBuilder input = new StringBuilder("@prefix ex: <http://example.com/> .\n");
		for (int i = 0; i < 10; i++) {
			input.append("ex:s").append(i).append(" ex:p \"value-").append(i).append("\" .\n");
		}
		Path inputFile = writeInput(tempDir, "input.ttl", input.toString());

		Path perStatementDir = tempDir.resolve("chunks-per-statement");
		assertEquals(10, TurtleChunker.writeChunks(inputFile, 1, perStatementDir, false));
		assertEquals(10, listRegularFiles(perStatementDir).size());
		assertEveryChunkParses(perStatementDir, RDFFormat.TURTLE);
		assertChunkUnionIsomorphic(inputFile, perStatementDir, RDFFormat.TURTLE);

		Path singleChunkDir = tempDir.resolve("chunks-single");
		assertEquals(1, TurtleChunker.writeChunks(inputFile, 1L << 30, singleChunkDir, false));
		assertEquals(1, listRegularFiles(singleChunkDir).size());
		assertChunkUnionIsomorphic(inputFile, singleChunkDir, RDFFormat.TURTLE);
	}

	@Test
	void blankNodeStatementsFromDefaultAndNamedGraphsShareOneRewrappedFile(@TempDir Path tempDir)
			throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				_:b1 ex:p "top" .
				ex:r1 ex:p "r1" .
				{ _:b2 ex:p "anon" . }
				ex:r2 ex:p "r2" .
				ex:g { _:b3 ex:p "named" . }
				ex:r3 ex:p "r3" .
				GRAPH ex:h { _:b4 ex:p "kw" . }
				ex:r4 ex:p "r4" .
				""";
		Path inputFile = writeInput(tempDir, "input.trig", input);

		Path outputDir = tempDir.resolve("chunks");
		int chunkCount = TurtleChunker.writeChunks(inputFile, 1, outputDir, false);

		List<Path> blankNodeChunks = listRegularFiles(outputDir).stream()
				.filter(chunk -> readString(chunk).contains("_:"))
				.toList();
		assertEquals(1, blankNodeChunks.size(), "All labeled blank node statements share one file");
		String blankNodeChunk = readString(blankNodeChunks.getFirst());
		assertTrue(blankNodeChunk.contains("ex:g {"), "Blank node file rewraps the named graph");
		assertTrue(blankNodeChunk.contains("GRAPH ex:h {"), "Blank node file rewraps the GRAPH-keyword graph");

		assertEquals(5, chunkCount, "Four regular chunks plus one blank node chunk");
		assertEveryChunkParses(outputDir, RDFFormat.TRIG);
		assertChunkUnionIsomorphic(inputFile, outputDir, RDFFormat.TRIG);
	}

	@Test
	void lateDirectiveReachesBlankNodeChunkAfterGraphContent(@TempDir Path tempDir) throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				ex:g { _:b1 ex:p "one" . }
				@prefix foo: <http://example.com/foo#> .
				foo:s foo:p _:b1 .
				""";
		Path inputFile = writeInput(tempDir, "input.trig", input);

		Path outputDir = tempDir.resolve("chunks");
		TurtleChunker.writeChunks(inputFile, 1, outputDir, false);

		List<Path> blankNodeChunks = listRegularFiles(outputDir).stream()
				.filter(chunk -> readString(chunk).contains("_:"))
				.toList();
		assertEquals(1, blankNodeChunks.size());
		String blankNodeChunk = readString(blankNodeChunks.getFirst());
		int graphClose = blankNodeChunk.lastIndexOf('}');
		int lateDirective = blankNodeChunk.indexOf("@prefix foo:");
		assertTrue(lateDirective >= 0, "Late directive must be written to the blank node chunk");
		assertTrue(graphClose < lateDirective,
				"The open graph wrap must be closed before the inline directive");
		assertEveryChunkParses(outputDir, RDFFormat.TRIG);
		assertChunkUnionIsomorphic(inputFile, outputDir, RDFFormat.TRIG);
	}

	@Test
	void formatChunkFileNamePadsAndGrowsPastFiveDigits() {
		assertEquals("chunk-00001.ttl", TurtleChunker.formatChunkFileName(1, ".ttl"));
		assertEquals("chunk-99999.trig", TurtleChunker.formatChunkFileName(99_999, ".trig"));
		// Past five digits the name grows; note that lexicographic file ordering no longer
		// matches chunk ordering from chunk-100000 onwards.
		assertEquals("chunk-100000.ttl", TurtleChunker.formatChunkFileName(100_000, ".ttl"));
		assertEquals("chunk-2147483647.ttl", TurtleChunker.formatChunkFileName(Integer.MAX_VALUE, ".ttl"));
	}

	@Test
	void emptyFileProducesNoChunks(@TempDir Path tempDir) throws IOException {
		Path inputFile = writeInput(tempDir, "input.ttl", "");
		Path outputDir = tempDir.resolve("chunks");
		assertEquals(0, TurtleChunker.writeChunks(inputFile, 64, outputDir, false));
		assertTrue(Files.isDirectory(outputDir), "Output directory is created even for empty input");
		assertEquals(0, listRegularFiles(outputDir).size());
	}

	@Test
	void whitespaceOnlyFileProducesNoChunks(@TempDir Path tempDir) throws IOException {
		Path inputFile = writeInput(tempDir, "input.ttl", " \n\t \r\n  \n");
		Path outputDir = tempDir.resolve("chunks");
		assertEquals(0, TurtleChunker.writeChunks(inputFile, 64, outputDir, false));
		assertEquals(0, listRegularFiles(outputDir).size());
	}

	@Test
	void commentOnlyFileProducesOneChunkWithNoStatements(@TempDir Path tempDir) throws IOException {
		Path inputFile = writeInput(tempDir, "input.ttl", "# just a comment\n");
		Path outputDir = tempDir.resolve("chunks");
		int chunkCount = TurtleChunker.writeChunks(inputFile, 64, outputDir, false);

		assertEquals(1, chunkCount, "The trailing comment is emitted as a leftover block");
		String chunk = readString(listRegularFiles(outputDir).getFirst());
		assertTrue(chunk.contains("# just a comment"));
		assertEquals(0, parse(chunk, RDFFormat.TURTLE).size());
	}

	@Test
	void directivesOnlyFileProducesNoChunks(@TempDir Path tempDir) throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				@base <http://example.com/base/> .
				""";
		Path inputFile = writeInput(tempDir, "input.ttl", input);
		Path outputDir = tempDir.resolve("chunks");
		assertEquals(0, TurtleChunker.writeChunks(inputFile, 64, outputDir, false),
				"Directives accumulate in the header but never open a chunk on their own");
		assertEquals(0, listRegularFiles(outputDir).size());
	}

	@Test
	void nonexistentInputFileThrowsIoException(@TempDir Path tempDir) {
		Path missing = tempDir.resolve("missing.ttl");
		IOException e = assertThrows(IOException.class,
				() -> TurtleChunker.writeChunks(missing, 64, tempDir.resolve("chunks"), false));
		assertTrue(e.getMessage().startsWith("Input file not found: "));
	}

	@Test
	void directoryAsInputThrowsIoException(@TempDir Path tempDir) throws IOException {
		Path directory = Files.createDirectory(tempDir.resolve("dir.ttl"));
		IOException e = assertThrows(IOException.class,
				() -> TurtleChunker.writeChunks(directory, 64, tempDir.resolve("chunks"), false));
		assertTrue(e.getMessage().startsWith("Input file not found: "));
	}

	@Test
	void zeroChunkSizeThrowsIllegalArgumentException(@TempDir Path tempDir) throws IOException {
		Path inputFile = writeInput(tempDir, "input.ttl", "ex:s ex:p ex:o .\n");
		assertThrows(IllegalArgumentException.class,
				() -> TurtleChunker.writeChunks(inputFile, 0, tempDir.resolve("chunks"), false));
	}

	@Test
	void existingOutputFilesAreOverwrittenAndStaleFilesRemain(@TempDir Path tempDir) throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				ex:s1 ex:p "one" .
				ex:s2 ex:p "two" .
				""";
		Path inputFile = writeInput(tempDir, "input.ttl", input);

		Path outputDir = Files.createDirectory(tempDir.resolve("chunks"));
		Files.writeString(outputDir.resolve("chunk-00001.ttl"), "stale garbage");
		Files.writeString(outputDir.resolve("chunk-00009.ttl"), "stale garbage");
		Files.writeString(outputDir.resolve("unrelated.txt"), "not a chunk");

		int chunkCount = TurtleChunker.writeChunks(inputFile, 1, outputDir, false);

		// Pinned hazard: pre-existing chunk files are overwritten in place, but stale files from
		// an earlier, larger run are NOT cleaned up and will corrupt naive directory consumers.
		assertEquals(2, chunkCount);
		assertTrue(readString(outputDir.resolve("chunk-00001.ttl")).contains("ex:s1"));
		assertEquals("stale garbage", readString(outputDir.resolve("chunk-00009.ttl")));
		assertEquals("not a chunk", readString(outputDir.resolve("unrelated.txt")));
	}

	@ParameterizedTest(name = "{0}, readBufferSize={3}, chunkSize={4}")
	@MethodSource("readBufferAndChunkSizeGrid")
	void readBufferSizeAndChunkSizeGrid(String name, String fileName, String input, int readBufferSize,
			long chunkSize, @TempDir Path tempDir) throws IOException {
		Path inputFile = writeInput(tempDir, fileName, input);
		Path outputDir = tempDir.resolve("chunks");

		int chunkCount = TurtleChunker.writeChunks(inputFile, chunkSize, outputDir, false,
				System.out, System::currentTimeMillis, readBufferSize);

		RDFFormat format = fileName.endsWith(".trig") ? RDFFormat.TRIG : RDFFormat.TURTLE;
		assertEquals(listRegularFiles(outputDir).size(), chunkCount);
		assertEveryChunkParses(outputDir, format);
		assertChunkUnionIsomorphic(inputFile, outputDir, format);
	}

	private static Stream<Arguments> readBufferAndChunkSizeGrid() {
		int[] readBufferSizes = {1, 2, 3, 4, 8, 13, 4096};
		long[] chunkSizes = {1, 4096};
		return Stream.of(
						Arguments.of("turtle-rich", "input.ttl", RdfTestSupport.turtleRichInput()),
						Arguments.of("turtle-rich-bom", "input.ttl", "\uFEFF" + RdfTestSupport.turtleRichInput()),
						Arguments.of("trig-rich", "input.trig", RdfTestSupport.trigRichInput()))
				.flatMap(fixture -> java.util.stream.IntStream.of(readBufferSizes)
						.boxed()
						.flatMap(readBufferSize -> LongStream.of(chunkSizes)
								.mapToObj(chunkSize -> Arguments.of(fixture.get()[0], fixture.get()[1],
										fixture.get()[2], readBufferSize, chunkSize))));
	}

	private static Path writeInput(Path tempDir, String fileName, String input) throws IOException {
		Path path = tempDir.resolve(fileName);
		Files.writeString(path, input, StandardCharsets.UTF_8);
		return path;
	}

	private static String readString(Path path) {
		try {
			return Files.readString(path, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}
}
