package no.hasmac.ttlchunker;

import static no.hasmac.ttlchunker.RdfTestSupport.assertChunkUnionIsomorphic;
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

class TurtleChunkerDirectiveTest {

	@Test
	void latePrefixIsWrittenToAlreadyOpenTurtleChunk(@TempDir Path tempDir) throws IOException {
		Path input = writeInput(tempDir, "input.ttl", """
				@prefix ex: <http://example.com/> .
				ex:s ex:p "before" .
				@prefix foaf: <http://xmlns.com/foaf/0.1/> .
				ex:s foaf:name "after" .
				""");

		Path outputDir = tempDir.resolve("chunks");
		assertEquals(1, TurtleChunker.writeChunks(input, 1_000_000, outputDir, false));

		assertEveryChunkParses(outputDir, RDFFormat.TURTLE);
		assertChunkUnionIsomorphic(input, outputDir, RDFFormat.TURTLE);
		String chunk = Files.readString(listRegularFiles(outputDir).getFirst(), StandardCharsets.UTF_8);
		assertTrue(chunk.contains("@prefix foaf: <http://xmlns.com/foaf/0.1/> ."));
	}

	@Test
	void lateBaseIsWrittenToAlreadyOpenTurtleChunk(@TempDir Path tempDir) throws IOException {
		Path input = writeInput(tempDir, "input.ttl", """
				@base <http://example.com/base/> .
				<first> <p> <o> .
				@base <http://example.org/other/> .
				<second> <p> <o> .
				""");

		Path outputDir = tempDir.resolve("chunks");
		assertEquals(1, TurtleChunker.writeChunks(input, 1_000_000, outputDir, false));

		assertEveryChunkParses(outputDir, RDFFormat.TURTLE);
		assertChunkUnionIsomorphic(input, outputDir, RDFFormat.TURTLE);
		String chunk = Files.readString(listRegularFiles(outputDir).getFirst(), StandardCharsets.UTF_8);
		assertTrue(chunk.contains("@base <http://example.org/other/> ."));
	}

	@Test
	void latePrefixIsWrittenToAlreadyOpenBlankNodeChunk(@TempDir Path tempDir) throws IOException {
		Path input = writeInput(tempDir, "input.ttl", """
				@prefix ex: <http://example.com/> .
				_:shared ex:p "before" .
				@prefix foaf: <http://xmlns.com/foaf/0.1/> .
				_:shared foaf:name "after" .
				""");

		Path outputDir = tempDir.resolve("chunks");
		assertEquals(1, TurtleChunker.writeChunks(input, 1_000_000, outputDir, false));

		assertEveryChunkParses(outputDir, RDFFormat.TURTLE);
		assertChunkUnionIsomorphic(input, outputDir, RDFFormat.TURTLE);
		String chunk = Files.readString(listRegularFiles(outputDir).getFirst(), StandardCharsets.UTF_8);
		assertTrue(chunk.contains("@prefix foaf: <http://xmlns.com/foaf/0.1/> ."));
		assertTrue(chunk.contains("_:shared foaf:name \"after\" ."));
	}

	@Test
	void latePrefixAfterTrigGraphClosesGraphBeforeDirective(@TempDir Path tempDir) throws IOException {
		Path input = writeInput(tempDir, "input.trig", """
				@prefix ex: <http://example.com/> .
				ex:g {
				  ex:s ex:p "before" .
				}
				@prefix foaf: <http://xmlns.com/foaf/0.1/> .
				ex:g {
				  ex:s foaf:name "after" .
				}
				""");

		Path outputDir = tempDir.resolve("chunks");
		assertEquals(1, TurtleChunker.writeChunks(input, 1_000_000, outputDir, false));

		assertEveryChunkParses(outputDir, RDFFormat.TRIG);
		assertChunkUnionIsomorphic(input, outputDir, RDFFormat.TRIG);
		String chunk = Files.readString(listRegularFiles(outputDir).getFirst(), StandardCharsets.UTF_8);
		assertTrue(chunk.contains("ex:s ex:p \"before\" .\n}\n@prefix foaf:"));
		assertTrue(chunk.contains("ex:g {\nex:s foaf:name \"after\" .\n}\n"));
	}

	// The redefinition tests below prove that re-emitting the full directive history at the top of
	// every chunk preserves semantics: every statement in a chunk postdates every header directive,
	// so the last binding in the header is the binding that was in effect for that statement.

	@Test
	void baseRedefinitionAcrossChunkRotation(@TempDir Path tempDir) throws IOException {
		Path input = writeInput(tempDir, "input.ttl", """
				@base <http://a/> .
				<s1> <p> <o1> .
				<s2> <p> <o2> .
				@base <http://b/> .
				<s3> <p> <o3> .
				<s4> <p> <o4> .
				""");

		Path outputDir = tempDir.resolve("chunks");
		assertEquals(4, TurtleChunker.writeChunks(input, 1, outputDir, false));

		assertEveryChunkParses(outputDir, RDFFormat.TURTLE);
		assertChunkUnionIsomorphic(input, outputDir, RDFFormat.TURTLE);
	}

	@Test
	void relativeBaseChainResolvesAcrossChunks(@TempDir Path tempDir) throws IOException {
		Path input = writeInput(tempDir, "input.ttl", """
				@base <http://a/x/> .
				<s1> <p> <o1> .
				@base <y/> .
				<s2> <p> <o2> .
				""");

		Path outputDir = tempDir.resolve("chunks");
		assertEquals(2, TurtleChunker.writeChunks(input, 1, outputDir, false));

		assertEveryChunkParses(outputDir, RDFFormat.TURTLE);
		assertChunkUnionIsomorphic(input, outputDir, RDFFormat.TURTLE);
	}

	@Test
	void prefixRebindingAcrossChunkRotation(@TempDir Path tempDir) throws IOException {
		Path input = writeInput(tempDir, "input.ttl", """
				@prefix p: <http://a/> .
				p:s1 p:p p:o1 .
				@prefix p: <http://b/> .
				p:s2 p:p p:o2 .
				""");

		Path outputDir = tempDir.resolve("chunks");
		assertEquals(2, TurtleChunker.writeChunks(input, 1, outputDir, false));

		assertEveryChunkParses(outputDir, RDFFormat.TURTLE);
		assertChunkUnionIsomorphic(input, outputDir, RDFFormat.TURTLE);
	}

	@Test
	void redefinitionWithBlankNodeChunkSpanningBothBindings(@TempDir Path tempDir) throws IOException {
		Path input = writeInput(tempDir, "input.ttl", """
				@prefix p: <http://a/> .
				_:x p:p p:o1 .
				p:r1 p:p "regular" .
				@prefix p: <http://b/> .
				_:y p:p p:o2 .
				p:r2 p:p "regular2" .
				""");

		Path outputDir = tempDir.resolve("chunks");
		TurtleChunker.writeChunks(input, 1, outputDir, false);

		String blankNodeChunk = listRegularFiles(outputDir).stream()
				.map(TurtleChunkerDirectiveTest::readString)
				.filter(chunk -> chunk.contains("_:"))
				.findFirst()
				.orElseThrow();
		int firstBlankNode = blankNodeChunk.indexOf("_:x");
		int rebind = blankNodeChunk.lastIndexOf("@prefix p: <http://b/> .");
		int secondBlankNode = blankNodeChunk.indexOf("_:y");
		assertTrue(firstBlankNode < rebind && rebind < secondBlankNode,
				"The rebinding directive must sit between the two blank node statements");
		assertEveryChunkParses(outputDir, RDFFormat.TURTLE);
		assertChunkUnionIsomorphic(input, outputDir, RDFFormat.TURTLE);
	}

	@Test
	void redefinitionBeforeLazilyOpenedBlankNodeChunk(@TempDir Path tempDir) throws IOException {
		Path input = writeInput(tempDir, "input.ttl", """
				@prefix p: <http://a/> .
				p:r1 p:p "r1" .
				@prefix p: <http://b/> .
				_:x p:p p:o .
				""");

		Path outputDir = tempDir.resolve("chunks");
		TurtleChunker.writeChunks(input, 1, outputDir, false);

		assertEveryChunkParses(outputDir, RDFFormat.TURTLE);
		assertChunkUnionIsomorphic(input, outputDir, RDFFormat.TURTLE);
	}

	@Test
	void baseRedefinitionWithBlankNodeStatements(@TempDir Path tempDir) throws IOException {
		Path input = writeInput(tempDir, "input.ttl", """
				@base <http://a/> .
				_:x <p> <o1> .
				@base <http://b/> .
				_:y <p> <o2> .
				""");

		Path outputDir = tempDir.resolve("chunks");
		TurtleChunker.writeChunks(input, 1, outputDir, false);

		assertEveryChunkParses(outputDir, RDFFormat.TURTLE);
		assertChunkUnionIsomorphic(input, outputDir, RDFFormat.TURTLE);
	}

	@Test
	void prefixRebindingOfGraphLabelAcrossChunks(@TempDir Path tempDir) throws IOException {
		Path input = writeInput(tempDir, "input.trig", """
				@prefix p: <http://a/> .
				p:g {
				  p:s p:p "one" .
				}
				@prefix p: <http://b/> .
				p:g {
				  p:s p:p "two" .
				}
				""");

		Path outputDir = tempDir.resolve("chunks");
		assertEquals(2, TurtleChunker.writeChunks(input, 1, outputDir, false));

		assertEveryChunkParses(outputDir, RDFFormat.TRIG);
		assertChunkUnionIsomorphic(input, outputDir, RDFFormat.TRIG);
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
