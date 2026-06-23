package no.hasmac.ttlchunker;

import static no.hasmac.ttlchunker.RdfTestSupport.assertChunkUnionIsomorphic;
import static no.hasmac.ttlchunker.RdfTestSupport.assertEveryChunkParses;
import static no.hasmac.ttlchunker.RdfTestSupport.listRegularFiles;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TurtleChunkerGeneratedTest {
	private static final long[] CHUNK_SIZES = {1, 7, 31, 127, 4096};

	@Test
	void generatedTurtleCasesParseAndPreserveModel(@TempDir Path tempDir) throws IOException {
		for (int i = 0; i < 50; i++) {
			long seed = 12_345L + i;
			String input = generateTurtle(seed);
			assertGeneratedCase(tempDir, "ttl-" + i, "input.ttl", input, RDFFormat.TURTLE, seed);
		}
	}

	@Test
	void generatedTrigCasesParseAndPreserveModel(@TempDir Path tempDir) throws IOException {
		for (int i = 0; i < 50; i++) {
			long seed = 54_321L + i;
			String input = generateTrig(seed);
			assertGeneratedCase(tempDir, "trig-" + i, "input.trig", input, RDFFormat.TRIG, seed);
		}
	}

	private static void assertGeneratedCase(Path tempDir, String caseName, String fileName, String input,
			RDFFormat format, long seed) throws IOException {
		Path caseDir = tempDir.resolve(caseName);
		Files.createDirectories(caseDir);
		Path inputFile = caseDir.resolve(fileName);
		Files.writeString(inputFile, input, StandardCharsets.UTF_8);
		Path outputDir = caseDir.resolve("chunks");
		long chunkSize = CHUNK_SIZES[(int) Math.floorMod(seed, CHUNK_SIZES.length)];

		int chunkCount = TurtleChunker.writeChunks(inputFile, chunkSize, outputDir, false);

		assertEquals(listRegularFiles(outputDir).size(), chunkCount, "seed=" + seed);
		assertEveryChunkParses(outputDir, format);
		assertChunkUnionIsomorphic(inputFile, outputDir, format);
	}

	private static String generateTurtle(long seed) {
		Random random = new Random(seed);
		int statements = 8 + random.nextInt(10);
		int directiveIndex = statements / 2;
		StringBuilder ttl = new StringBuilder(statements * 80);
		appendPrefixes(ttl);
		for (int i = 0; i < statements; i++) {
			if (i == directiveIndex) {
				ttl.append("@prefix gen: <http://generated.example/> .\n");
			}
			appendStatement(ttl, random, i, i >= directiveIndex);
		}
		return ttl.toString();
	}

	private static String generateTrig(long seed) {
		Random random = new Random(seed);
		int statements = 8 + random.nextInt(10);
		int directiveIndex = statements / 2;
		StringBuilder trig = new StringBuilder(statements * 96);
		appendPrefixes(trig);
		for (int i = 0; i < statements; i++) {
			if (i == directiveIndex) {
				trig.append("@prefix gen: <http://generated.example/> .\n");
			}
			appendTrigUnit(trig, random, i, i >= directiveIndex);
		}
		return trig.toString();
	}

	private static void appendPrefixes(StringBuilder rdf) {
		rdf.append("@prefix ex: <http://example.com/> .\n");
		rdf.append("@prefix foaf: <http://xmlns.com/foaf/0.1/> .\n");
	}

	private static void appendTrigUnit(StringBuilder trig, Random random, int index, boolean generatedPrefixAvailable) {
		switch (random.nextInt(4)) {
			case 0 -> appendStatement(trig, random, index, generatedPrefixAvailable);
			case 1 -> {
				trig.append("{\n");
				appendStatement(trig, random, index, generatedPrefixAvailable);
				trig.append("}\n");
			}
			case 2 -> {
				trig.append("ex:g").append(index % 4).append(" {\n");
				appendStatement(trig, random, index, generatedPrefixAvailable);
				trig.append("}\n");
			}
			default -> {
				trig.append("GRAPH ex:g").append(index % 4).append(" {\n");
				appendStatement(trig, random, index, generatedPrefixAvailable);
				trig.append("}\n");
			}
		}
	}

	private static void appendStatement(StringBuilder rdf, Random random, int index, boolean generatedPrefixAvailable) {
		int variants = generatedPrefixAvailable ? 6 : 5;
		switch (random.nextInt(variants)) {
			case 0 -> rdf.append("ex:s").append(index).append(" ex:p \"value ")
					.append(index).append(" with . and { }\" .\n");
			case 1 -> rdf.append("ex:s").append(index).append(" ex:p ex:o")
					.append(random.nextInt(20)).append(" .\n");
			case 2 -> rdf.append("ex:s").append(index).append(" ex:list ( ex:o")
					.append(index).append(" \"item\" ex:o").append(index + 1).append(" ) .\n");
			case 3 -> rdf.append("_:b").append(index % 3).append(" ex:p \"blank ")
					.append(index).append("\" .\n");
			case 4 -> rdf.append("ex:s").append(index).append(" ex:link _:b")
					.append(index % 3).append(" .\n");
			default -> rdf.append("ex:s").append(index).append(" gen:p ex:o")
					.append(index).append("; foaf:name \"generated ")
					.append(index).append("\" .\n");
		}
	}
}
