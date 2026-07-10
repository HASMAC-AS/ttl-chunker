package no.hasmac.ttlchunker;

import static no.hasmac.ttlchunker.RdfTestSupport.assertChunkUnionIsomorphic;
import static no.hasmac.ttlchunker.RdfTestSupport.assertEveryChunkParses;
import static no.hasmac.ttlchunker.RdfTestSupport.listRegularFiles;
import static no.hasmac.ttlchunker.RdfTestSupport.parse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TurtleChunkerDiscoveredBugTest {

	@Test
	void sparqlStylePrefixIsAvailableInEveryChunk(@TempDir Path tempDir) throws IOException {
		String input = """
				PREFIX ex: <http://example.com/>
				ex:s1 ex:p "one" .
				ex:s2 ex:p "two" .
				""";
		assertDoesNotThrow(() -> parse(input, RDFFormat.TURTLE), "Input is valid Turtle");

		Path inputFile = writeInput(tempDir, "input.ttl", input);
		Path outputDir = tempDir.resolve("chunks");
		int chunkCount = TurtleChunker.writeChunks(inputFile, 1, outputDir, false);

		assertEquals(2, chunkCount);
		assertDoesNotThrow(() -> assertEveryChunkParses(outputDir, RDFFormat.TURTLE),
				"Prefix must be available to every chunk");
		assertDoesNotThrow(() -> assertChunkUnionIsomorphic(inputFile, outputDir, RDFFormat.TURTLE));
	}

	@Test
	void utf8BomBeforeDirectiveDoesNotBreakChunking(@TempDir Path tempDir) throws IOException {
		String input = "\uFEFF" + """
				@prefix ex: <http://example.com/> .
				ex:s1 ex:p "one" .
				ex:s2 ex:p "two" .
				""";
		assertDoesNotThrow(() -> parse(input, RDFFormat.TURTLE), "Input is valid Turtle");

		Path inputFile = writeInput(tempDir, "input.ttl", input);
		Path outputDir = tempDir.resolve("chunks");
		int chunkCount = assertDoesNotThrow(() -> TurtleChunker.writeChunks(inputFile, 1, outputDir, false),
				"UTF-8 BOM before a directive must be ignored");

		assertEquals(2, chunkCount);
		assertDoesNotThrow(() -> assertEveryChunkParses(outputDir, RDFFormat.TURTLE));
		assertDoesNotThrow(() -> assertChunkUnionIsomorphic(inputFile, outputDir, RDFFormat.TURTLE));
	}

	@Test
	void trigBlankNodeGraphLabelIsNotSplitAcrossChunkFiles(@TempDir Path tempDir) throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				_:graph {
				  ex:s1 ex:p "one" .
				  ex:s2 ex:p "two" .
				}
				""";
		assertDoesNotThrow(() -> parse(input, RDFFormat.TRIG), "Input is valid TriG");

		Path inputFile = writeInput(tempDir, "input.trig", input);
		Path outputDir = tempDir.resolve("chunks");
		int chunkCount = TurtleChunker.writeChunks(inputFile, 1, outputDir, false);

		assertEquals(1, chunkCount, "Blank node graph labels are scoped to one file");
		String chunk = Files.readString(listRegularFiles(outputDir).getFirst(), StandardCharsets.UTF_8);
		assertTrue(chunk.contains("_:graph {\nex:s1 ex:p \"one\" ."));
		assertTrue(chunk.contains("ex:s2 ex:p \"two\" .\n}\n"));
		assertDoesNotThrow(() -> assertEveryChunkParses(outputDir, RDFFormat.TRIG));
		assertDoesNotThrow(() -> assertChunkUnionIsomorphic(inputFile, outputDir, RDFFormat.TRIG));
	}

	// Bug: the final triple in a TriG graph block may omit its '.' per the grammar
	// (triplesBlock ::= triples ('.' triplesBlock?)?), but the chunker used to reject '}'
	// whenever unterminated content preceded it.

	@Test
	void dotlessFinalTripleInGraphIsEmitted(@TempDir Path tempDir) throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				ex:g {
				  ex:s1 ex:p "one" .
				  ex:s2 ex:p "two"
				}
				""";
		assertDoesNotThrow(() -> parse(input, RDFFormat.TRIG), "Input is valid TriG");

		Path inputFile = writeInput(tempDir, "input.trig", input);
		assertGraphChunksRoundTrip(inputFile, tempDir);

		Path outputDir = tempDir.resolve("chunks-default");
		String allChunks = readAllChunks(outputDir);
		assertTrue(allChunks.contains("ex:s2 ex:p \"two\""), "The dot-less final triple must be emitted");
	}

	@Test
	void dotImmediatelyBeforeClosingBrace(@TempDir Path tempDir) throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				ex:g { ex:s ex:p ex:o .}
				""";
		assertDoesNotThrow(() -> parse(input, RDFFormat.TRIG), "Input is valid TriG");

		Path inputFile = writeInput(tempDir, "input.trig", input);
		assertGraphChunksRoundTrip(inputFile, tempDir);
	}

	@Test
	void dotlessTripleInAnonymousGraph(@TempDir Path tempDir) throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				{ ex:s ex:p ex:o }
				""";
		assertDoesNotThrow(() -> parse(input, RDFFormat.TRIG), "Input is valid TriG");

		Path inputFile = writeInput(tempDir, "input.trig", input);
		assertGraphChunksRoundTrip(inputFile, tempDir);
	}

	@Test
	void dotlessTripleWithGraphKeyword(@TempDir Path tempDir) throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				GRAPH ex:g { ex:s ex:p ex:o }
				""";
		assertDoesNotThrow(() -> parse(input, RDFFormat.TRIG), "Input is valid TriG");

		Path inputFile = writeInput(tempDir, "input.trig", input);
		assertGraphChunksRoundTrip(inputFile, tempDir);
	}

	@Test
	void emptyThenNonEmptyDotlessGraphs(@TempDir Path tempDir) throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				ex:g { }
				ex:g2 { ex:s ex:p "value" }
				""";
		assertDoesNotThrow(() -> parse(input, RDFFormat.TRIG), "Input is valid TriG");

		Path inputFile = writeInput(tempDir, "input.trig", input);
		assertGraphChunksRoundTrip(inputFile, tempDir);
	}

	// Bug: SPARQL-style directives were cut at the end of the LINE instead of the end of their
	// IRI, so a statement sharing the directive's line was swallowed into every chunk header
	// (or, split across lines, left a dangling fragment there).

	@Test
	void sparqlPrefixAndTripleOnSameLine(@TempDir Path tempDir) throws IOException {
		String input = """
				PREFIX ex: <http://example.com/>  ex:s1 ex:p "one" .
				ex:s2 ex:p "two" .
				""";
		assertDoesNotThrow(() -> parse(input, RDFFormat.TURTLE), "Input is valid Turtle");

		Path inputFile = writeInput(tempDir, "input.ttl", input);
		Path outputDir = tempDir.resolve("chunks");
		int chunkCount = TurtleChunker.writeChunks(inputFile, 1, outputDir, false);

		assertEquals(2, chunkCount, "The triple on the directive's line is a statement, not header");
		String lastChunk = Files.readString(listRegularFiles(outputDir).getLast(), StandardCharsets.UTF_8);
		assertTrue(!lastChunk.contains("ex:s1"), "Later chunk headers must not smuggle the first triple");
		assertEveryChunkParses(outputDir, RDFFormat.TURTLE);
		assertChunkUnionIsomorphic(inputFile, outputDir, RDFFormat.TURTLE);
	}

	@Test
	void sparqlPrefixWithTripleContinuingOnNextLine(@TempDir Path tempDir) throws IOException {
		String input = """
				PREFIX ex: <http://example.com/> ex:s ex:p
				  "o" .
				""";
		assertDoesNotThrow(() -> parse(input, RDFFormat.TURTLE), "Input is valid Turtle");

		Path inputFile = writeInput(tempDir, "input.ttl", input);
		Path outputDir = tempDir.resolve("chunks");
		assertEquals(1, TurtleChunker.writeChunks(inputFile, 1, outputDir, false));

		assertEveryChunkParses(outputDir, RDFFormat.TURTLE);
		assertChunkUnionIsomorphic(inputFile, outputDir, RDFFormat.TURTLE);
	}

	@Test
	void sparqlBaseAndTripleOnSameLine(@TempDir Path tempDir) throws IOException {
		String input = """
				BASE <http://example.com/base/> <s> <p> <o> .
				<s2> <p2> <o2> .
				""";
		assertDoesNotThrow(() -> parse(input, RDFFormat.TURTLE), "Input is valid Turtle");

		Path inputFile = writeInput(tempDir, "input.ttl", input);
		Path outputDir = tempDir.resolve("chunks");
		assertEquals(2, TurtleChunker.writeChunks(inputFile, 1, outputDir, false));

		assertEveryChunkParses(outputDir, RDFFormat.TURTLE);
		assertChunkUnionIsomorphic(inputFile, outputDir, RDFFormat.TURTLE);
	}

	// Bug: the UTF-8 BOM was only skipped when the very first read returned at least 3 bytes,
	// so read buffers smaller than the BOM leaked it into the content (turning a following
	// directive into a syntax error).

	@Test
	void utf8BomIsSkippedWithTinyReadBuffer(@TempDir Path tempDir) throws IOException {
		String input = "\uFEFF" + """
				@prefix ex: <http://example.com/> .
				ex:s1 ex:p "one" .
				ex:s2 ex:p "two" .
				""";
		assertDoesNotThrow(() -> parse(input, RDFFormat.TURTLE), "Input is valid Turtle");
		Path inputFile = writeInput(tempDir, "input.ttl", input);

		for (int readBufferSize : new int[] {1, 2}) {
			Path outputDir = tempDir.resolve("chunks-" + readBufferSize);
			int chunkCount = assertDoesNotThrow(() -> TurtleChunker.writeChunks(inputFile, 1, outputDir,
					false, System.out, System::currentTimeMillis, readBufferSize),
					"BOM must be skipped even when the first read returns fewer than 3 bytes");

			assertEquals(2, chunkCount);
			assertEveryChunkParses(outputDir, RDFFormat.TURTLE);
			assertChunkUnionIsomorphic(inputFile, outputDir, RDFFormat.TURTLE);
		}
	}

	// Bug: an anonymous graph label ('[] { … }') mints a fresh blank node every time it is
	// re-emitted, so splitting such a graph across chunks (or merging adjacent ones in the blank
	// node chunk) changes the dataset. ANON-labeled graphs must be colocated like blank-node
	//-labeled ones, and each block must keep its own wrap.

	@Test
	void anonymousGraphLabelIsNotSplitAcrossChunks(@TempDir Path tempDir) throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				[] {
				  ex:s1 ex:p "one" .
				  ex:s2 ex:p "two" .
				}
				""";
		assertDoesNotThrow(() -> parse(input, RDFFormat.TRIG), "Input is valid TriG");

		Path inputFile = writeInput(tempDir, "input.trig", input);
		Path outputDir = tempDir.resolve("chunks");
		assertEquals(1, TurtleChunker.writeChunks(inputFile, 1, outputDir, false),
				"An anonymous graph label is scoped to one file");
		assertEveryChunkParses(outputDir, RDFFormat.TRIG);
		assertChunkUnionIsomorphic(inputFile, outputDir, RDFFormat.TRIG);
	}

	@Test
	void adjacentAnonymousGraphsStayDistinct(@TempDir Path tempDir) throws IOException {
		// Two '[]' blocks are two different graphs; colocating them in one file must not merge
		// them under a single wrap.
		String input = """
				@prefix ex: <http://example.com/> .
				[] { ex:s1 ex:p "one" . }
				[] { ex:s2 ex:p "two" . }
				""";
		assertDoesNotThrow(() -> parse(input, RDFFormat.TRIG), "Input is valid TriG");

		Path inputFile = writeInput(tempDir, "input.trig", input);
		Path outputDir = tempDir.resolve("chunks");
		TurtleChunker.writeChunks(inputFile, 1, outputDir, false);

		assertEveryChunkParses(outputDir, RDFFormat.TRIG);
		assertChunkUnionIsomorphic(inputFile, outputDir, RDFFormat.TRIG);
	}

	// Bugs found by the W3C TriG suite: SPARQL-style directives are dot-less, so they can end up
	// scanned into the same block as what follows them — a graph header, a comment-led block, or
	// an @-directive — and the write-time directive extraction missed all three shapes.

	@Test
	void sparqlPrefixDirectlyBeforeGraphBlock(@TempDir Path tempDir) throws IOException {
		// Shape of W3C SPARQL_style_prefix: the directive must reach the prefix store, not be
		// captured into the graph header.
		String input = """
				PREFIX p: <http://a.example/>
				{p:s <http://a.example/p> <http://a.example/o> .}
				<http://example/graph> {p:s <http://a.example/p> <http://a.example/o> .}
				""";
		assertDoesNotThrow(() -> parse(input, RDFFormat.TRIG), "Input is valid TriG");

		Path inputFile = writeInput(tempDir, "input.trig", input);
		Path outputDir = tempDir.resolve("chunks");
		TurtleChunker.writeChunks(inputFile, 1, outputDir, false);

		assertEveryChunkParses(outputDir, RDFFormat.TRIG);
		assertChunkUnionIsomorphic(inputFile, outputDir, RDFFormat.TRIG);
	}

	@Test
	void commentBeforeSparqlDirective(@TempDir Path tempDir) throws IOException {
		// Shape of W3C trig-turtle-01: a comment line shares the block with the dot-less
		// directive; detection must look past it.
		String input = """
				# Turtle is TriG
				PREFIX : <http://example/>

				:s :p :o ;
				   :q 123 , 456 .

				:s1 :p1 "more" .
				""";
		assertDoesNotThrow(() -> parse(input, RDFFormat.TRIG), "Input is valid TriG");

		Path inputFile = writeInput(tempDir, "input.trig", input);
		Path outputDir = tempDir.resolve("chunks");
		assertEquals(2, TurtleChunker.writeChunks(inputFile, 1, outputDir, false));

		assertEveryChunkParses(outputDir, RDFFormat.TRIG);
		assertChunkUnionIsomorphic(inputFile, outputDir, RDFFormat.TRIG);
	}

	@Test
	void atDirectiveAfterDotlessSparqlDirective(@TempDir Path tempDir) throws IOException {
		// Shape of W3C trig-syntax-minimal-whitespace-01: 'BASE<iri>' (no whitespace — valid) has
		// no terminator, so the following '@base' used to look like an '@' mid-statement and threw.
		String input = """
				BASE<http://example/base>
				@base<http://example/base>.
				PREFIX :<http://example/a/>
				@prefix d: <http://example/d/> .
				:s :p d:o .
				<relative> :p :o .
				""";
		assertDoesNotThrow(() -> parse(input, RDFFormat.TRIG), "Input is valid TriG");

		Path inputFile = writeInput(tempDir, "input.trig", input);
		Path outputDir = tempDir.resolve("chunks");
		assertEquals(2, assertDoesNotThrow(() -> TurtleChunker.writeChunks(inputFile, 1, outputDir, false)));

		assertEveryChunkParses(outputDir, RDFFormat.TRIG);
		assertChunkUnionIsomorphic(inputFile, outputDir, RDFFormat.TRIG);
	}

	@Test
	void sparqlBaseDirectlyBeforeGraphBlock(@TempDir Path tempDir) throws IOException {
		// Shape of W3C SPARQL_style_base.
		String input = """
				BASE <http://a.example/>
				{<s> <http://a.example/p> <http://a.example/o> .}
				<http://example/graph> {<s2> <http://a.example/p> <http://a.example/o> .}
				""";
		assertDoesNotThrow(() -> parse(input, RDFFormat.TRIG), "Input is valid TriG");

		Path inputFile = writeInput(tempDir, "input.trig", input);
		Path outputDir = tempDir.resolve("chunks");
		TurtleChunker.writeChunks(inputFile, 1, outputDir, false);

		assertEveryChunkParses(outputDir, RDFFormat.TRIG);
		assertChunkUnionIsomorphic(inputFile, outputDir, RDFFormat.TRIG);
	}

	private static void assertGraphChunksRoundTrip(Path inputFile, Path tempDir) throws IOException {
		Path outputDir = tempDir.resolve("chunks-default");
		assertDoesNotThrow(() -> TurtleChunker.writeChunks(inputFile, 1, outputDir, false),
				"Valid TriG must not be rejected");
		assertEveryChunkParses(outputDir, RDFFormat.TRIG);
		assertChunkUnionIsomorphic(inputFile, outputDir, RDFFormat.TRIG);

		Path smallReadOutputDir = tempDir.resolve("chunks-small-read");
		assertDoesNotThrow(() -> TurtleChunker.writeChunks(inputFile, 1, smallReadOutputDir, false,
				System.out, System::currentTimeMillis, 4));
		assertEveryChunkParses(smallReadOutputDir, RDFFormat.TRIG);
		assertChunkUnionIsomorphic(inputFile, smallReadOutputDir, RDFFormat.TRIG);
	}

	private static String readAllChunks(Path outputDir) throws IOException {
		StringBuilder allChunks = new StringBuilder();
		for (Path chunk : listRegularFiles(outputDir)) {
			allChunks.append(Files.readString(chunk, StandardCharsets.UTF_8));
		}
		return allChunks.toString();
	}

	private static Path writeInput(Path tempDir, String fileName, String input) throws IOException {
		Path path = tempDir.resolve(fileName);
		Files.writeString(path, input, StandardCharsets.UTF_8);
		return path;
	}
}
