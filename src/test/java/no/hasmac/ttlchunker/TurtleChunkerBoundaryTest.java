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
import java.util.List;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TurtleChunkerBoundaryTest {

	@Test
	void smallReadsPreserveLexicalBoundaries(@TempDir Path tempDir) throws IOException {
		Path inputFile = writeInput(tempDir, "input.ttl", """
				@prefix ex: <http://example.com/really/long/> .
				ex:s <http://example.com/iri/with%7Bbrace%7D> "escaped \\" literal with . and }" .
				ex:s ex:multi '''line one
				line two . } still literal''' .
				# comment . } ignored
				_:shared ex:p "blank" .
				ex:tail ex:p "period eof" .""");

		Path outputDir = tempDir.resolve("chunks");
		assertEquals(4, TurtleChunker.writeChunks(inputFile, 1, outputDir, false,
				System.out, System::currentTimeMillis, 7));

		assertEveryChunkParses(outputDir, RDFFormat.TURTLE);
		assertChunkUnionIsomorphic(inputFile, outputDir, RDFFormat.TURTLE);

		List<Path> chunks = listRegularFiles(outputDir);
		assertTrue(read(chunks.get(0)).contains(
				"ex:s <http://example.com/iri/with%7Bbrace%7D> \"escaped \\\" literal with . and }\" ."));
		assertTrue(read(chunks.get(1)).contains("ex:s ex:multi '''line one\nline two . } still literal''' ."));
		assertTrue(read(chunks.get(2)).contains("# comment . } ignored\n_:shared ex:p \"blank\" ."));
		assertTrue(read(chunks.get(3)).contains("ex:tail ex:p \"period eof\" ."));
	}

	@Test
	void smallReadsAttachGraphHeaderToTrigStatement(@TempDir Path tempDir) throws IOException {
		Path inputFile = writeInput(tempDir, "input.trig", """
				@prefix ex: <http://example.com/> .
				GRAPH ex:graphName {
				  ex:s ex:p "value" .
				}""");

		Path outputDir = tempDir.resolve("chunks");
		assertEquals(1, TurtleChunker.writeChunks(inputFile, 1, outputDir, false,
				System.out, System::currentTimeMillis, 5));

		assertEveryChunkParses(outputDir, RDFFormat.TRIG);
		assertChunkUnionIsomorphic(inputFile, outputDir, RDFFormat.TRIG);
		assertTrue(read(listRegularFiles(outputDir).getFirst())
				.contains("GRAPH ex:graphName {\nex:s ex:p \"value\" .\n}\n"));
	}

	@Test
	void periodAtEofEmitsStatement(@TempDir Path tempDir) throws IOException {
		Path inputFile = writeInput(tempDir, "input.ttl", """
				@prefix ex: <http://example.com/> .
				ex:s ex:p ex:o .""");

		Path outputDir = tempDir.resolve("chunks");
		assertEquals(1, TurtleChunker.writeChunks(inputFile, 1, outputDir, false,
				System.out, System::currentTimeMillis, 4));

		assertEveryChunkParses(outputDir, RDFFormat.TURTLE);
		assertChunkUnionIsomorphic(inputFile, outputDir, RDFFormat.TURTLE);
		assertTrue(read(listRegularFiles(outputDir).getFirst()).contains("ex:s ex:p ex:o ."));
	}

	private static Path writeInput(Path tempDir, String fileName, String input) throws IOException {
		Path path = tempDir.resolve(fileName);
		Files.writeString(path, input, StandardCharsets.UTF_8);
		return path;
	}

	private static String read(Path path) throws IOException {
		return Files.readString(path, StandardCharsets.UTF_8);
	}
}
