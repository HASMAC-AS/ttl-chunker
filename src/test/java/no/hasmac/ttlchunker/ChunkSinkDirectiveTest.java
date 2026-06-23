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

class ChunkSinkDirectiveTest {

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

	private static Path writeInput(Path tempDir, String fileName, String input) throws IOException {
		Path path = tempDir.resolve(fileName);
		Files.writeString(path, input, StandardCharsets.UTF_8);
		return path;
	}
}
