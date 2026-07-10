package no.hasmac.ttlchunker;

import static no.hasmac.ttlchunker.RdfTestSupport.assertChunkUnionIsomorphic;
import static no.hasmac.ttlchunker.RdfTestSupport.assertChunkingRoundTrips;
import static no.hasmac.ttlchunker.RdfTestSupport.assertEveryChunkParses;
import static no.hasmac.ttlchunker.RdfTestSupport.parse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Numeric literals and bare keywords. The tricky part for a chunker is that '.' is both the
 * statement terminator and a character inside decimals, doubles, and prefixed names — these tests
 * pin the disambiguation ("dot followed by whitespace at nesting depth zero terminates").
 */
class TurtleChunkerNumberAndKeywordTest {

	@Test
	void integerFollowedByStatementDotSplitsAsTwoStatements(@TempDir Path tempDir) throws IOException {
		// '5.' is the INTEGER 5 followed by the statement dot (DECIMAL requires digits after the
		// dot), so this input is two statements.
		String input = """
				@prefix ex: <http://example.com/> .
				ex:s ex:p 5. ex:s2 ex:p 6 .
				""";
		Path inputFile = assertChunkingRoundTrips(tempDir, "input.ttl", input);

		Path outputDir = tempDir.resolve("chunks-count");
		assertEquals(2, TurtleChunker.writeChunks(inputFile, 1, outputDir, false),
				"'5.' must terminate the first statement");
	}

	@Test
	void integerDotAtEofTerminatesFinalStatement(@TempDir Path tempDir) throws IOException {
		String input = "@prefix ex: <http://example.com/> .\nex:s ex:p 5.";
		Path inputFile = assertChunkingRoundTrips(tempDir, "input.ttl", input);

		Path outputDir = tempDir.resolve("chunks-count");
		assertEquals(1, TurtleChunker.writeChunks(inputFile, 1, outputDir, false));
	}

	@Test
	void decimalWithTrailingStatementDotIsOneBoundary(@TempDir Path tempDir) throws IOException {
		// The dot inside '1.5' is followed by a digit and must not terminate; only the final dot
		// does.
		String input = """
				@prefix ex: <http://example.com/> .
				ex:s ex:p 1.5.
				""";
		Path inputFile = assertChunkingRoundTrips(tempDir, "input.ttl", input);

		Path outputDir = tempDir.resolve("chunks-count");
		assertEquals(1, TurtleChunker.writeChunks(inputFile, 1, outputDir, false));
	}

	@Test
	void allNumericFormsSurviveChunking(@TempDir Path tempDir) throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				ex:s1 ex:p 1 .
				ex:s2 ex:p -5 .
				ex:s3 ex:p +7 .
				ex:s4 ex:p 1.5 .
				ex:s5 ex:p .5 .
				ex:s6 ex:p -2.5E-3 .
				ex:s7 ex:p +.5e0 .
				ex:s8 ex:p 1e10 .
				ex:s9 ex:p 1.e3 .
				""";
		Path inputFile = assertChunkingRoundTrips(tempDir, "input.ttl", input);

		Path outputDir = tempDir.resolve("chunks-count");
		assertEquals(9, TurtleChunker.writeChunks(inputFile, 1, outputDir, false),
				"Every numeric form must be one statement");
	}

	@Test
	void aKeywordAndBooleanLiteralsSurviveChunking(@TempDir Path tempDir) throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				ex:s a ex:Type .
				ex:s ex:p true .
				ex:s ex:q false .
				""";
		assertChunkingRoundTrips(tempDir, "input.ttl", input);
	}

	@Test
	void statementDotFollowedByCommentWithoutWhitespace(@TempDir Path tempDir) throws IOException {
		// '.#comment' is a valid statement terminator per the grammar. The chunker requires
		// whitespace (or EOF) after the dot, so it under-splits: both statements land in one
		// block. The output is still valid and the model is preserved — the pinned chunk count
		// makes the divergence explicit.
		String input = """
				@prefix ex: <http://example.com/> .
				ex:s ex:p ex:o .#c
				ex:s2 ex:p ex:o2 .
				""";
		assertDoesNotThrow(() -> parse(input, RDFFormat.TURTLE), "Input is valid Turtle");
		Path inputFile = writeInput(tempDir, "input.ttl", input);

		Path outputDir = tempDir.resolve("chunks");
		int chunkCount = TurtleChunker.writeChunks(inputFile, 1, outputDir, false);

		assertEquals(1, chunkCount, "Pinned divergence: '.#' is not recognized as a boundary");
		assertEveryChunkParses(outputDir, RDFFormat.TURTLE);
		assertChunkUnionIsomorphic(inputFile, outputDir, RDFFormat.TURTLE);
	}

	@Test
	void statementDotFollowedImmediatelyByNextSubject(@TempDir Path tempDir) throws IOException {
		// A '.' directly followed by the next subject's '<' is a valid boundary per the grammar;
		// the chunker under-splits here too. Model preservation is what matters — pinned count
		// documents the divergence.
		String input = "<http://example.com/s> <http://example.com/p> <http://example.com/o> ."
				+ "<http://example.com/s2> <http://example.com/p2> <http://example.com/o2> .\n";
		assertDoesNotThrow(() -> parse(input, RDFFormat.TURTLE), "Input is valid Turtle");
		Path inputFile = writeInput(tempDir, "input.ttl", input);

		Path outputDir = tempDir.resolve("chunks");
		int chunkCount = TurtleChunker.writeChunks(inputFile, 1, outputDir, false);

		assertEquals(1, chunkCount, "Pinned divergence: '.<' is not recognized as a boundary");
		assertEveryChunkParses(outputDir, RDFFormat.TURTLE);
		assertChunkUnionIsomorphic(inputFile, outputDir, RDFFormat.TURTLE);
	}

	private static Path writeInput(Path tempDir, String fileName, String input) throws IOException {
		Path path = tempDir.resolve(fileName);
		Files.writeString(path, input, StandardCharsets.UTF_8);
		return path;
	}
}
