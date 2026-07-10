package no.hasmac.ttlchunker;

import static no.hasmac.ttlchunker.RdfTestSupport.assertChunkUnionIsomorphic;
import static no.hasmac.ttlchunker.RdfTestSupport.assertChunkingRoundTrips;
import static no.hasmac.ttlchunker.RdfTestSupport.assertEveryChunkParses;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * String literal and IRI lexical edge cases: every delimiter, escape, and embedded-terminator
 * variant the scanner has to see through.
 */
class TurtleChunkerLiteralAndIriTest {

	@Test
	void emptyShortLiteralsBothQuoteStylesSurviveChunking(@TempDir Path tempDir) throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				ex:s ex:p "" .
				ex:s ex:q '' .
				""";
		assertChunkingRoundTrips(tempDir, "input.ttl", input);
	}

	@Test
	void longStringWithEmbeddedQuoteRunsSurviveChunking(@TempDir Path tempDir) throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				ex:s ex:p \"\"\" "" " \"\"\" .
				ex:s ex:q '''a''b''' .
				""";
		assertChunkingRoundTrips(tempDir, "input.ttl", input);
	}

	@Test
	void longStringStartingWithQuoteSurvivesChunking(@TempDir Path tempDir) throws IOException {
		// '""""x"""' is a long string whose content starts with a quote character.
		String input = """
				@prefix ex: <http://example.com/> .
				ex:s ex:p \"\"\"\"x\"\"\" .
				ex:s ex:q ''''y''' .
				""";
		assertChunkingRoundTrips(tempDir, "input.ttl", input);
	}

	@Test
	void emptyLongStringsSurviveChunking(@TempDir Path tempDir) throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				ex:s ex:p \"\"\"\"\"\" .
				ex:s ex:q '''''' .
				""";
		assertChunkingRoundTrips(tempDir, "input.ttl", input);
	}

	@Test
	void longStringContainingTerminatorsAndBlankLinesSurvivesChunking(@TempDir Path tempDir)
			throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				ex:s ex:p \"\"\"line one .
				{ not a graph }

				ex:fake ex:statement "inside" .
				\"\"\" .
				ex:s2 ex:p "after" .
				""";
		Path inputFile = assertChunkingRoundTrips(tempDir, "input.ttl", input);

		Path outputDir = tempDir.resolve("chunks-count");
		assertEquals(2, TurtleChunker.writeChunks(inputFile, 1, outputDir, false),
				"Terminators inside a long string must not split the statement");
	}

	@Test
	void escapesInShortLiteralsSurviveChunking(@TempDir Path tempDir) throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				ex:s1 ex:p "quote \\" inside" .
				ex:s2 ex:p "backslash \\\\" .
				ex:s3 ex:p "newline \\n tab \\t" .
				ex:s4 ex:p "unicode \\u00E9 and \\U0001F600" .
				""";
		assertChunkingRoundTrips(tempDir, "input.ttl", input);
	}

	@Test
	void escapedQuoteAtEveryReadBufferAlignment(@TempDir Path tempDir) throws IOException {
		// Sweep read buffer sizes 1..8 so the backslash-quote pair straddles every possible
		// buffer boundary alignment.
		String input = """
				@prefix ex: <http://example.com/> .
				ex:s ex:p "ab\\"cd\\\\" .
				ex:s2 ex:p "plain" .
				""";
		Path inputFile = tempDir.resolve("input.ttl");
		Files.writeString(inputFile, input, StandardCharsets.UTF_8);

		for (int readBufferSize = 1; readBufferSize <= 8; readBufferSize++) {
			Path outputDir = tempDir.resolve("chunks-" + readBufferSize);
			TurtleChunker.writeChunks(inputFile, 1, outputDir, false,
					System.out, System::currentTimeMillis, readBufferSize);
			assertEveryChunkParses(outputDir, org.eclipse.rdf4j.rio.RDFFormat.TURTLE);
			assertChunkUnionIsomorphic(inputFile, outputDir, org.eclipse.rdf4j.rio.RDFFormat.TURTLE);
		}
	}

	@Test
	void languageTagsAndSubtagsSurviveChunking(@TempDir Path tempDir) throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				ex:s1 ex:p "x"@en .
				ex:s2 ex:p "x"@en-GB-oed .
				ex:s3 ex:p "x"@en.
				""";
		assertChunkingRoundTrips(tempDir, "input.ttl", input);
	}

	@Test
	void datatypedLiteralsSurviveChunking(@TempDir Path tempDir) throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
				ex:s1 ex:p "1"^^xsd:int .
				ex:s2 ex:p "x"^^<http://example.com/dt.with.dots> .
				""";
		assertChunkingRoundTrips(tempDir, "input.ttl", input);
	}

	@Test
	void relativeAndEmptyIrisResolveConsistentlyAcrossChunks(@TempDir Path tempDir) throws IOException {
		String input = """
				@base <http://example.com/base/> .
				<> <a> <../up> .
				<a> <b> <#frag> .
				""";
		assertChunkingRoundTrips(tempDir, "input.ttl", input);
	}

	@Test
	void iriContainingDotsSemicolonsCommasAndHash(@TempDir Path tempDir) throws IOException {
		String input = """
				<http://example.com/a.b.c;d,e#f> <http://example.com/p.q> <http://example.com/o.z> .
				<http://example.com/s2> <http://example.com/p2> <http://example.com/a.b.c;d,e#f> .
				""";
		assertChunkingRoundTrips(tempDir, "input.ttl", input);
	}

	@Test
	void numericEscapesInsideIrisSurviveChunking(@TempDir Path tempDir) throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				ex:s ex:p <http://example.com/\\u00E9> .
				ex:s2 ex:p <http://example.com/\\U0001F600> .
				""";
		assertChunkingRoundTrips(tempDir, "input.ttl", input);
	}

	@Test
	void iriLongerThanReadBufferSurvivesChunking(@TempDir Path tempDir) throws IOException {
		String longIri = "<http://example.com/" + "segment/".repeat(30) + "leaf>";
		String input = longIri + " <http://example.com/p> <http://example.com/o> .\n";
		assertChunkingRoundTrips(tempDir, "input.ttl", input);
	}
}
