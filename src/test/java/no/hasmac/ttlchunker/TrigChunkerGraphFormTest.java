package no.hasmac.ttlchunker;

import static no.hasmac.ttlchunker.RdfTestSupport.assertChunkingRoundTrips;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every valid TriG graph-block form: label kinds crossed with the optional GRAPH keyword, empty
 * graphs, interleaved top-level triples, repeated labels, and layout variations.
 */
class TrigChunkerGraphFormTest {

	@ParameterizedTest(name = "label={0}, keyword={1}")
	@MethodSource("graphLabelMatrix")
	void allGraphLabelFormsWithAndWithoutGraphKeyword(String label, boolean graphKeyword,
			@TempDir Path tempDir) throws IOException {
		String header = (graphKeyword ? "GRAPH " : "") + label;
		String input = """
				@prefix ex: <http://example.com/> .
				%s {
				  ex:s1 ex:p "one" .
				  ex:s2 ex:p "two" .
				}
				ex:top ex:p "top-level" .
				""".formatted(header);
		assertChunkingRoundTrips(tempDir, "input.trig", input);
	}

	private static Stream<Arguments> graphLabelMatrix() {
		return Stream.of("<http://example.com/g>", "ex:g", "_:g", "[]")
				.flatMap(label -> Stream.of(Arguments.of(label, false), Arguments.of(label, true)));
	}

	@Test
	void emptyGraphsInEveryFormSurviveChunking(@TempDir Path tempDir) throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				{}
				{ }
				ex:g {}
				GRAPH ex:g2 { }
				""";
		assertChunkingRoundTrips(tempDir, "input.trig", input);
	}

	@Test
	void graphKeywordLowercaseSurvivesChunking(@TempDir Path tempDir) throws IOException {
		// The GRAPH keyword is case-insensitive in TriG (like SPARQL-form PREFIX/BASE).
		String input = """
				@prefix ex: <http://example.com/> .
				graph ex:g { ex:s ex:p ex:o . }
				Graph ex:g2 { ex:s2 ex:p ex:o2 . }
				""";
		assertChunkingRoundTrips(tempDir, "input.trig", input);
	}

	@Test
	void topLevelTriplesInterleavedBetweenGraphBlocks(@TempDir Path tempDir) throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				ex:before ex:p "before" .
				ex:g1 { ex:s1 ex:p "one" . }
				ex:between ex:p "between" .
				ex:g2 { ex:s2 ex:p "two" . }
				ex:after ex:p "after" .
				""";
		assertChunkingRoundTrips(tempDir, "input.trig", input);
	}

	@Test
	void sameGraphLabelInMultipleBlocksMergesInUnion(@TempDir Path tempDir) throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				ex:g { ex:s1 ex:p "one" . }
				ex:other { ex:s ex:p "elsewhere" . }
				ex:g { ex:s2 ex:p "two" . }
				""";
		assertChunkingRoundTrips(tempDir, "input.trig", input);
	}

	@Test
	void graphLabelResemblingKeyword(@TempDir Path tempDir) throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				ex:GRAPH { ex:s ex:p "label looks like keyword" . }
				ex:true { ex:s ex:p "label looks like boolean" . }
				""";
		assertChunkingRoundTrips(tempDir, "input.trig", input);
	}

	@Test
	void bracesOnTheirOwnLinesAndCommentOnlyGraph(@TempDir Path tempDir) throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				ex:g
				{
				ex:s ex:p ex:o .
				}
				ex:empty
				{
				# just a comment . }
				}
				""";
		assertChunkingRoundTrips(tempDir, "input.trig", input);
	}

	@Test
	void pureTurtleContentInTrigFileSurvivesChunking(@TempDir Path tempDir) throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				ex:s1 ex:p "one" .
				ex:s2 ex:p "two" .
				""";
		assertChunkingRoundTrips(tempDir, "input.trig", input);
	}

	@Test
	void finalGraphBlockAtEofWithoutTrailingNewline(@TempDir Path tempDir) throws IOException {
		String input = "@prefix ex: <http://example.com/> .\nex:g { ex:s ex:p ex:o . }";
		assertChunkingRoundTrips(tempDir, "input.trig", input);
	}

	@Test
	void graphBlocksSplitAndRewrapAtChunkBoundaries(@TempDir Path tempDir) throws IOException {
		StringBuilder graphBody = new StringBuilder();
		for (int i = 0; i < 10; i++) {
			graphBody.append("  ex:s").append(i).append(" ex:p \"value-").append(i).append("\" .\n");
		}
		String input = """
				@prefix ex: <http://example.com/> .
				ex:g {
				%s}
				""".formatted(graphBody);
		Path inputFile = assertChunkingRoundTrips(tempDir, "input.trig", input);

		Path outputDir = tempDir.resolve("chunks-count");
		assertEquals(10, TurtleChunker.writeChunks(inputFile, 1, outputDir, false),
				"Each statement gets its own rewrapped chunk at chunk size 1");
	}
}
