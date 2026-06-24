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

	private static Path writeInput(Path tempDir, String fileName, String input) throws IOException {
		Path path = tempDir.resolve(fileName);
		Files.writeString(path, input, StandardCharsets.UTF_8);
		return path;
	}
}
