package no.hasmac.ttlchunker;

import static no.hasmac.ttlchunker.RobustnessAssertions.DEFAULT_READ_BUFFER;
import static no.hasmac.ttlchunker.RobustnessAssertions.runChunkerClassified;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import no.hasmac.ttlchunker.RobustnessAssertions.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the chunker's behavior on input that does not follow the Turtle/TriG standard. Every case
 * asserts the global robustness contract (terminates; only TurtleSyntaxException or normal
 * completion) plus the concrete current outcome, so any behavior change is visible. The chunker is
 * not a validator: most malformed input passes through verbatim, which is the documented contract.
 */
class TurtleChunkerMalformedInputTest {

	private static final int[] READ_BUFFER_SIZES = {4, DEFAULT_READ_BUFFER};

	@Test
	void unterminatedShortStringAtEof(@TempDir Path tempDir) throws IOException {
		assertRobust(tempDir, "input.ttl", "ex:s ex:p \"never closed", Outcome.COMPLETES, "\"never closed");
	}

	@Test
	void unterminatedLongStringAtEof(@TempDir Path tempDir) throws IOException {
		assertRobust(tempDir, "input.ttl", "ex:s ex:p \"\"\"abc", Outcome.COMPLETES, "\"\"\"abc");
	}

	@Test
	void unterminatedIriAtEof(@TempDir Path tempDir) throws IOException {
		assertRobust(tempDir, "input.ttl", "ex:s ex:p <http://never", Outcome.COMPLETES, "<http://never");
	}

	@Test
	void rawNewlineInShortString(@TempDir Path tempDir) throws IOException {
		assertRobust(tempDir, "input.ttl", "ex:s ex:p \"line\nbreak\" .\n", Outcome.COMPLETES, "line\nbreak");
	}

	@Test
	void missingFinalDot(@TempDir Path tempDir) throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				ex:s ex:p ex:o""";
		assertRobust(tempDir, "input.ttl", input, Outcome.COMPLETES, "ex:s ex:p ex:o");
	}

	@Test
	void dotAsFirstCharacter(@TempDir Path tempDir) throws IOException {
		assertRobust(tempDir, "input.ttl", ". ex:s ex:p ex:o .\n", Outcome.COMPLETES);
	}

	@Test
	void doubleDots(@TempDir Path tempDir) throws IOException {
		assertRobust(tempDir, "input.ttl", "ex:s ex:p ex:o . .\n", Outcome.COMPLETES);
	}

	@Test
	void strayCloseBraceAtTopLevel(@TempDir Path tempDir) throws IOException {
		// Outside a graph block '}' is treated as ordinary content, not a syntax error.
		assertRobust(tempDir, "input.ttl", "}\nex:s ex:p ex:o .\n", Outcome.COMPLETES, "}");
	}

	@Test
	void graphBlockInTtlInput(@TempDir Path tempDir) throws IOException {
		// The input extension only affects chunk file naming; braces are chunked as TriG anyway.
		assertRobust(tempDir, "input.ttl", "{ ex:s ex:p ex:o . }\n", Outcome.COMPLETES, "ex:s ex:p ex:o .");
	}

	@Test
	void nestedGraphBraces(@TempDir Path tempDir) throws IOException {
		assertRobust(tempDir, "input.trig", "ex:g { { ex:s ex:p ex:o . } }\n", Outcome.THROWS);
	}

	@Test
	void eofInsideGraph(@TempDir Path tempDir) throws IOException {
		assertRobust(tempDir, "input.trig", "ex:g { ex:s ex:p ex:o .\n", Outcome.THROWS);
	}

	@Test
	void eofInsideBracketList(@TempDir Path tempDir) throws IOException {
		assertRobust(tempDir, "input.ttl", "ex:s ex:p [ ex:q ex:o", Outcome.COMPLETES);
	}

	@Test
	void eofInsideCollection(@TempDir Path tempDir) throws IOException {
		assertRobust(tempDir, "input.ttl", "ex:s ex:p ( ex:a ex:b", Outcome.COMPLETES);
	}

	@Test
	void mismatchedBracketTypes(@TempDir Path tempDir) throws IOException {
		assertRobust(tempDir, "input.ttl", "ex:s ex:p ( ex:o ] .\n", Outcome.COMPLETES);
	}

	@Test
	void closeParenWithoutOpener(@TempDir Path tempDir) throws IOException {
		assertRobust(tempDir, "input.ttl", "ex:s ex:p ex:o ) .\n", Outcome.COMPLETES);
	}

	@Test
	void closeBracketWithoutOpener(@TempDir Path tempDir) throws IOException {
		assertRobust(tempDir, "input.ttl", "] ex:p ex:o .\n", Outcome.COMPLETES);
	}

	@Test
	void unknownAtDirective(@TempDir Path tempDir) throws IOException {
		assertRobust(tempDir, "input.ttl", "@unknown <http://example.com/> .\n", Outcome.THROWS);
	}

	@Test
	void atDirectiveWrongWordRightLetter(@TempDir Path tempDir) throws IOException {
		// Divergence from the spec: only the first letter after '@' is validated (p/b,
		// case-insensitive), so '@parody' is accepted as if it were a prefix directive.
		assertRobust(tempDir, "input.ttl", "@parody ex: <http://example.com/> .\n", Outcome.COMPLETES);
	}

	@Test
	void atPrefixIsCaseSensitiveDivergencePinned(@TempDir Path tempDir) throws IOException {
		// '@prefix'/'@base' are case-SENSITIVE per the Turtle spec (only the SPARQL-style
		// PREFIX/BASE forms are case-insensitive), so '@PREFIX'/'@BASE' are invalid Turtle.
		// The chunker accepts them — a knowing divergence.
		assertRobust(tempDir, "input.ttl", "@PREFIX ex: <http://example.com/> .\n", Outcome.COMPLETES);
		assertRobust(tempDir, "input2.ttl", "@BASE <http://example.com/> .\n", Outcome.COMPLETES);
	}

	@Test
	void atPrefixMissingTrailingDot(@TempDir Path tempDir) throws IOException {
		// The directive block only ends at the next '.', so the following statement is swallowed
		// into the accumulated prefix header. Documented lenient behavior, not fixed.
		String input = """
				@prefix ex: <http://example.com/>
				ex:s ex:p ex:o .
				""";
		assertRobust(tempDir, "input.ttl", input, Outcome.COMPLETES);
	}

	@Test
	void atPrefixWithoutIri(@TempDir Path tempDir) throws IOException {
		assertRobust(tempDir, "input.ttl", "@prefix ex: .\n", Outcome.COMPLETES);
	}

	@Test
	void secondAtMidStatement(@TempDir Path tempDir) throws IOException {
		assertRobust(tempDir, "input.ttl", "ex:s ex:p @prefix .\n", Outcome.THROWS);
	}

	@Test
	void directiveInsideGraph(@TempDir Path tempDir) throws IOException {
		String input = """
				ex:g {
				@prefix ex: <http://example.com/> .
				ex:s ex:p ex:o .
				}
				""";
		assertRobust(tempDir, "input.trig", input, Outcome.COMPLETES);
	}

	@Test
	void trailingDotAfterGraphBrace(@TempDir Path tempDir) throws IOException {
		// Invalid TriG: the grammar has no '.' after a wrappedGraph. The chunker consumes and
		// discards it leniently.
		String input = """
				@prefix ex: <http://example.com/> .
				ex:g { ex:s ex:p ex:o . } .
				""";
		assertRobust(tempDir, "input.trig", input, Outcome.COMPLETES, "ex:s ex:p ex:o .");
	}

	@Test
	void baseDirectiveWithoutWhitespaceIsNotADirective(@TempDir Path tempDir) throws IOException {
		// RDF4J (the conformance oracle for this suite) rejects 'BASE<iri>' without whitespace
		// after the keyword, so the chunker does not treat it as a directive either; it passes
		// through as ordinary content.
		String input = """
				BASE<http://example.com/base/>
				<first> <p> <o> .
				""";
		assertRobust(tempDir, "input.ttl", input, Outcome.COMPLETES, "BASE<http://example.com/base/>");
	}

	@Test
	void bareAAsSubject(@TempDir Path tempDir) throws IOException {
		assertRobust(tempDir, "input.ttl", "a ex:p ex:o .\n", Outcome.COMPLETES);
	}

	@Test
	void langTagWithoutLiteral(@TempDir Path tempDir) throws IOException {
		// A bare '@' after content in a block is rejected (valid lang tags are pre-consumed
		// immediately after a closing quote and never reach this path).
		assertRobust(tempDir, "input.ttl", "ex:s ex:p @en .\n", Outcome.THROWS);
	}

	@Test
	void unknownStringEscape(@TempDir Path tempDir) throws IOException {
		assertRobust(tempDir, "input.ttl", "ex:s ex:p \"bad \\q escape\" .\n", Outcome.COMPLETES, "\\q");
	}

	@Test
	void invalidUtf8Bytes(@TempDir Path tempDir) throws IOException {
		// 0xC3 starts a 2-byte UTF-8 sequence but 0x28 is not a continuation byte. The chunker
		// never decodes, so the bytes pass through untouched.
		byte[] prefix = "ex:s ex:p \"".getBytes(StandardCharsets.US_ASCII);
		byte[] suffix = "\" .\n".getBytes(StandardCharsets.US_ASCII);
		byte[] input = new byte[prefix.length + 2 + suffix.length];
		System.arraycopy(prefix, 0, input, 0, prefix.length);
		input[prefix.length] = (byte) 0xC3;
		input[prefix.length + 1] = (byte) 0x28;
		System.arraycopy(suffix, 0, input, prefix.length + 2, suffix.length);
		assertRobust(tempDir, "input.ttl", input, Outcome.COMPLETES);
	}

	@Test
	void nulBytes(@TempDir Path tempDir) throws IOException {
		assertRobust(tempDir, "input.ttl", "ex:s ex:p \"a\0b\" .\n".getBytes(StandardCharsets.UTF_8),
				Outcome.COMPLETES);
	}

	@Test
	void rdfStarQuotedTriple(@TempDir Path tempDir) throws IOException {
		// RDF-star is documented as unsupported; the quoted triple passes through verbatim.
		assertRobust(tempDir, "input.ttl", "<< ex:s ex:p ex:o >> ex:q \"v\" .\n", Outcome.COMPLETES,
				"<< ex:s ex:p ex:o >>");
	}

	@Test
	void rdfStarAnnotationBlock(@TempDir Path tempDir) throws IOException {
		// The '{' of the annotation opens a bogus graph block that is closed leniently at '|}'.
		// RDF-star is documented as unsupported; the output is garbage-out, but no throw.
		assertRobust(tempDir, "input.ttl", "ex:s ex:p ex:o {| ex:a ex:b |} .\n", Outcome.COMPLETES);
	}

	@Test
	void trigGraphKeywordWithoutBrace(@TempDir Path tempDir) throws IOException {
		assertRobust(tempDir, "input.trig", "GRAPH ex:g ex:s ex:p ex:o .\n", Outcome.COMPLETES);
	}

	@Test
	void trailingGarbageAfterFinalDot(@TempDir Path tempDir) throws IOException {
		assertRobust(tempDir, "input.ttl", "ex:s ex:p ex:o .\nunterminated trailing tokens", Outcome.COMPLETES);
	}

	@Test
	void enormousSingleTokenAcrossBuffers(@TempDir Path tempDir) throws IOException {
		String input = "ex:s ex:p ex:" + "a".repeat(200_000) + " .\n";
		assertRobust(tempDir, "input.ttl", input, Outcome.COMPLETES, "ex:" + "a".repeat(200_000));
	}

	@Test
	void dotlessFinalTripleInGraph(@TempDir Path tempDir) throws IOException {
		// VALID TriG — the final triple's dot is optional inside a graph block. The positive
		// round-trip assertions live in TurtleChunkerDiscoveredBugTest; this pins no-throw.
		assertRobust(tempDir, "input.trig", "ex:g { ex:s ex:p ex:o }\n", Outcome.COMPLETES,
				"ex:s ex:p ex:o");
	}

	@Test
	void consecutiveSparqlDirectivesDeepRecursion(@TempDir Path tempDir) throws IOException {
		// SPARQL-style directives have no '.' terminator, so consecutive ones form one block that
		// is peeled apart at write time; this must be iterative, not one stack frame per directive.
		StringBuilder input = new StringBuilder();
		for (int i = 0; i < 50_000; i++) {
			input.append("PREFIX p").append(i).append(": <http://example.com/ns").append(i).append("#>\n");
		}
		input.append("<http://example.com/s> <http://example.com/p> <http://example.com/o> .\n");
		assertRobust(tempDir, "input.ttl", input.toString(), Outcome.COMPLETES);
	}

	@Test
	void bomSplitAcrossTinyFirstRead(@TempDir Path tempDir) throws IOException {
		// The first read is topped up to 3 bytes so the UTF-8 BOM is skipped even when the read
		// buffer is smaller than the BOM.
		byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
		byte[] triple = "<http://example.com/s> <http://example.com/p> <http://example.com/o> .\n"
				.getBytes(StandardCharsets.UTF_8);
		byte[] input = new byte[bom.length + triple.length];
		System.arraycopy(bom, 0, input, 0, bom.length);
		System.arraycopy(triple, 0, input, bom.length, triple.length);

		Path inputFile = tempDir.resolve("input.ttl");
		Files.write(inputFile, input);
		for (int readBufferSize : new int[] {1, 2}) {
			Path outputDir = tempDir.resolve("chunks-" + readBufferSize);
			Outcome outcome = runChunkerClassified(inputFile, 4096, outputDir, readBufferSize);
			assertEquals(Outcome.COMPLETES, outcome);
			byte[] chunk = Files.readAllBytes(RdfTestSupport.listRegularFiles(outputDir).getFirst());
			assertTrue(chunk[0] != (byte) 0xEF, "BOM must not leak into the first chunk");
		}
	}

	private static void assertRobust(Path tempDir, String fileName, String input, Outcome expected,
			String... expectedInOutput) throws IOException {
		assertRobust(tempDir, fileName, input.getBytes(StandardCharsets.UTF_8), expected, expectedInOutput);
	}

	private static void assertRobust(Path tempDir, String fileName, byte[] input, Outcome expected,
			String... expectedInOutput) throws IOException {
		Path inputFile = tempDir.resolve(fileName);
		Files.write(inputFile, input);

		for (int readBufferSize : READ_BUFFER_SIZES) {
			Path outputDir = tempDir.resolve(fileName + "-chunks-" + readBufferSize);
			Outcome outcome = runChunkerClassified(inputFile, 64, outputDir, readBufferSize);
			assertEquals(expected, outcome, "Pinned outcome changed (readBufferSize=" + readBufferSize + ")");

			if (outcome == Outcome.COMPLETES && expectedInOutput.length > 0) {
				StringBuilder allChunks = new StringBuilder();
				for (Path chunk : RdfTestSupport.listRegularFiles(outputDir)) {
					allChunks.append(Files.readString(chunk, StandardCharsets.UTF_8));
				}
				for (String fragment : expectedInOutput) {
					assertTrue(allChunks.toString().contains(fragment),
							"Chunk output must preserve input fragment: " + fragment
									+ " (readBufferSize=" + readBufferSize + ")");
				}
			}
		}
	}
}
