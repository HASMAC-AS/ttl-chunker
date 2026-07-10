package no.hasmac.ttlchunker;

import static no.hasmac.ttlchunker.RdfTestSupport.assertChunkingRoundTrips;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Whitespace, line endings, comment placement, and directive case variants — layout dimensions
 * orthogonal to the token-level tests.
 */
class TurtleChunkerLayoutTest {

	@Test
	void crlfLineEndingsEverywhereSurviveChunking(@TempDir Path tempDir) throws IOException {
		String input = "@prefix ex: <http://example.com/> .\r\n"
				+ "ex:s1 ex:p \"one\" .\r\n"
				+ "ex:g { ex:s2 ex:p \"two\" . }\r\n"
				+ "ex:s3 ex:p \"three\" .\r\n";
		assertChunkingRoundTrips(tempDir, "input.trig", input);
	}

	@Test
	void mixedLineEndingsAndTabsSurviveChunking(@TempDir Path tempDir) throws IOException {
		String input = "@prefix ex: <http://example.com/> .\r\n"
				+ "ex:s1\tex:p\t\"tabs\" .\n"
				+ "ex:s2 ex:p \"cr only\" .\r"
				+ "ex:s3 ex:p \"lf\" .\n";
		assertChunkingRoundTrips(tempDir, "input.ttl", input);
	}

	@Test
	void statementDotFollowedByCarriageReturnOnly(@TempDir Path tempDir) throws IOException {
		String input = "@prefix ex: <http://example.com/> .\rex:s ex:p ex:o .\r";
		assertChunkingRoundTrips(tempDir, "input.ttl", input);
	}

	@Test
	void commentsInEveryPositionSurviveChunking(@TempDir Path tempDir) throws IOException {
		String input = """
				# before the first directive
				@prefix ex: <http://example.com/> . # after a directive
				# between directive and statement, with . and } and { inside
				ex:s ex:p # between predicate and object
				  ex:o .
				ex:s2 # after the subject
				  ex:p2 "two" .
				""";
		assertChunkingRoundTrips(tempDir, "input.ttl", input);
	}

	@Test
	void commentAtEofWithoutTrailingNewline(@TempDir Path tempDir) throws IOException {
		String input = "@prefix ex: <http://example.com/> .\nex:s ex:p ex:o .\n# trailing comment";
		assertChunkingRoundTrips(tempDir, "input.ttl", input);
	}

	@Test
	void commentsInsideGraphBlocksSurviveChunking(@TempDir Path tempDir) throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				ex:g { # comment after the brace
				  ex:s ex:p "one" . # comment with } inside
				  # comment on its own line
				  ex:s2 ex:p "two" .
				}
				""";
		assertChunkingRoundTrips(tempDir, "input.trig", input);
	}

	@Test
	void sparqlStyleDirectivesAreCaseInsensitive(@TempDir Path tempDir) throws IOException {
		// SPARQL-form PREFIX/BASE (unlike '@prefix'/'@base') are case-insensitive per the spec.
		String input = """
				prefix ex: <http://example.com/>
				PrEfIx other: <http://example.org/>
				base <http://example.com/base/>
				ex:s ex:p other:o .
				<relative> ex:p "resolved against base" .
				ex:s2 ex:p "two" .
				""";
		assertChunkingRoundTrips(tempDir, "input.ttl", input);
	}

	@Test
	void statementsSeparatedOnlyByComments(@TempDir Path tempDir) throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				ex:s1 ex:p "one" . # comment directly after the dot
				# a comment is the only separator before the next statement
				ex:s2 ex:p "two" .
				""";
		assertChunkingRoundTrips(tempDir, "input.ttl", input);
	}
}
