package no.hasmac.ttlchunker;

import static no.hasmac.ttlchunker.RdfTestSupport.assertChunkUnionIsomorphic;
import static no.hasmac.ttlchunker.RdfTestSupport.assertChunkingRoundTrips;
import static no.hasmac.ttlchunker.RdfTestSupport.assertEveryChunkParses;
import static no.hasmac.ttlchunker.RdfTestSupport.listRegularFiles;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Prefixed-name lexical forms (dots, escapes, percent encoding), anonymous and labeled blank
 * nodes, collections, and raw multi-byte UTF-8 in every token position.
 */
class TurtleChunkerNameAndNodeTest {

	@Test
	void localNamesWithInteriorDotsSplitOnlyAtStatementDot(@TempDir Path tempDir) throws IOException {
		// 'ex:a.b.' at a statement end is local name 'a.b' plus the terminator dot: PN_LOCAL may
		// contain interior dots but cannot end with one.
		String input = """
				@prefix ex: <http://example.com/> .
				ex:a.b.c ex:p ex:o.x.y .
				ex:s ex:p ex:a.b. ex:s2 ex:p ex:c .
				""";
		Path inputFile = assertChunkingRoundTrips(tempDir, "input.ttl", input);

		Path outputDir = tempDir.resolve("chunks-count");
		assertEquals(3, TurtleChunker.writeChunks(inputFile, 1, outputDir, false),
				"'ex:a.b.' followed by whitespace ends the statement");
	}

	@Test
	void localNamesWithPnLocalEscapesSurviveChunking(@TempDir Path tempDir) throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				ex:foo\\.bar ex:p ex:o .
				ex:s ex:p ex:foo\\. .
				ex:a\\~b ex:p ex:a%2Fb .
				""";
		assertChunkingRoundTrips(tempDir, "input.ttl", input);
	}

	@Test
	void emptyPrefixSurvivesChunking(@TempDir Path tempDir) throws IOException {
		String input = """
				@prefix : <http://example.com/> .
				:x :p :o .
				:y :q "value" .
				""";
		assertChunkingRoundTrips(tempDir, "input.ttl", input);
	}

	@Test
	void anonymousBlankNodesDoNotOpenTheBlankNodeChunk(@TempDir Path tempDir) throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				[] ex:p ex:o .
				ex:s ex:p [] .
				[ ex:p [ ex:q 1 ] ] .
				ex:s ex:p [ ex:q 1 ; ex:r 2 ] .
				""";
		Path inputFile = assertChunkingRoundTrips(tempDir, "input.ttl", input);

		// Anonymous blank nodes are scoped to a single statement, so nothing routes to the
		// dedicated (never-rotated) blank node chunk: one big chunk holds everything.
		Path outputDir = tempDir.resolve("chunks-count");
		assertEquals(1, TurtleChunker.writeChunks(inputFile, 4096, outputDir, false),
				"Anonymous blank nodes must not open a separate blank node chunk");
	}

	@Test
	void collectionsEmptyNestedAndAsSubjectSurviveChunking(@TempDir Path tempDir) throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				ex:s ex:p () .
				ex:s ex:q (1 (2 3) "s") .
				(1 2) ex:p ex:o .
				""";
		Path inputFile = assertChunkingRoundTrips(tempDir, "input.ttl", input);

		Path outputDir = tempDir.resolve("chunks-count");
		assertEquals(1, TurtleChunker.writeChunks(inputFile, 4096, outputDir, false),
				"Collection blank nodes must not open a separate blank node chunk");
	}

	@Test
	void labeledBlankNodeLabelEdgeFormsRouteToBlankNodeChunk(@TempDir Path tempDir) throws IOException {
		// Leading digits, interior dots, and non-ASCII characters are all valid in
		// BLANK_NODE_LABEL; every statement using one shares the single blank node chunk.
		String input = """
				@prefix ex: <http://example.com/> .
				_:1x ex:p "leading digit" .
				ex:s ex:p _:a.b .
				_:åβ😀 ex:p _:1x .
				""";
		Path inputFile = assertChunkingRoundTrips(tempDir, "input.ttl", input);

		Path outputDir = tempDir.resolve("chunks-count");
		int chunkCount = TurtleChunker.writeChunks(inputFile, 1, outputDir, false);
		assertEquals(1, chunkCount, "All labeled blank node statements share one chunk");
		String chunk = Files.readString(listRegularFiles(outputDir).getFirst(), StandardCharsets.UTF_8);
		assertTrue(chunk.contains("_:1x"));
		assertTrue(chunk.contains("_:a.b"));
		assertTrue(chunk.contains("_:åβ😀"));
	}

	@Test
	void rawMultibyteUtf8EverywhereSurvivesChunking(@TempDir Path tempDir) throws IOException {
		// 2-byte (é), 3-byte (€), and 4-byte (😀) UTF-8 in IRIs, local names, and literals.
		String input = """
				@prefix ex: <http://example.com/é/> .
				ex:café ex:price "1 €" .
				<http://example.com/😀> ex:p "emoji 😀 in literal" .
				ex:s ex:p "mixed é€😀"@en .
				""";
		assertChunkingRoundTrips(tempDir, "input.ttl", input);
	}

	@Test
	void multibyteCharStraddlingReadBufferBoundary(@TempDir Path tempDir) throws IOException {
		// Sweep small read buffers so the 4-byte emoji straddles every alignment, both inside a
		// literal and inside an IRI. The scanner is byte-level, so this must be lossless.
		String input = """
				@prefix ex: <http://example.com/> .
				ex:s ex:p "emoji 😀 inside" .
				<http://example.com/😀path> ex:p ex:o .
				""";
		Path inputFile = tempDir.resolve("input.ttl");
		Files.writeString(inputFile, input, StandardCharsets.UTF_8);

		for (int readBufferSize = 1; readBufferSize <= 8; readBufferSize++) {
			Path outputDir = tempDir.resolve("chunks-" + readBufferSize);
			TurtleChunker.writeChunks(inputFile, 1, outputDir, false,
					System.out, System::currentTimeMillis, readBufferSize);
			assertEveryChunkParses(outputDir, RDFFormat.TURTLE);
			assertChunkUnionIsomorphic(inputFile, outputDir, RDFFormat.TURTLE);
		}
	}
}
