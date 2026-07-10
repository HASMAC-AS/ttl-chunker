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
		boolean crlf = random.nextBoolean();
		StringBuilder ttl = new StringBuilder(statements * 80);
		appendPrefixes(ttl);
		for (int i = 0; i < statements; i++) {
			if (i == directiveIndex) {
				ttl.append("@prefix gen: <http://generated.example/> .\n");
				ttl.append("PREFIX sparql: <http://sparql.example/>\n");
			}
			appendStatement(ttl, random, i, i >= directiveIndex);
		}
		return crlf ? ttl.toString().replace("\n", "\r\n") : ttl.toString();
	}

	private static String generateTrig(long seed) {
		Random random = new Random(seed);
		int statements = 8 + random.nextInt(10);
		int directiveIndex = statements / 2;
		boolean crlf = random.nextBoolean();
		StringBuilder trig = new StringBuilder(statements * 96);
		appendPrefixes(trig);
		for (int i = 0; i < statements; i++) {
			if (i == directiveIndex) {
				trig.append("@prefix gen: <http://generated.example/> .\n");
				trig.append("PREFIX sparql: <http://sparql.example/>\n");
			}
			appendTrigUnit(trig, random, i, i >= directiveIndex);
		}
		return crlf ? trig.toString().replace("\n", "\r\n") : trig.toString();
	}

	private static void appendPrefixes(StringBuilder rdf) {
		rdf.append("@prefix ex: <http://example.com/> .\n");
		rdf.append("@prefix foaf: <http://xmlns.com/foaf/0.1/> .\n");
	}

	private static void appendTrigUnit(StringBuilder trig, Random random, int index, boolean generatedPrefixAvailable) {
		switch (random.nextInt(6)) {
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
			case 3 -> // dot-less final triple: valid inside a graph block
					trig.append("ex:g").append(index % 4).append(" { ex:s").append(index)
							.append(" ex:p \"dotless ").append(index).append("\" }\n");
			case 4 -> {
				trig.append("ex:g").append(index % 4).append(" {\n");
				appendStatement(trig, random, index, generatedPrefixAvailable);
				appendStatement(trig, random, index + 100, generatedPrefixAvailable);
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
		if (random.nextInt(5) == 0) {
			rdf.append("# noise comment with . and } and { and \"quote\n");
		}
		int variants = generatedPrefixAvailable ? 16 : 15;
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
			case 5 -> rdf.append("ex:s").append(index).append(" ex:n ")
					.append(switch (random.nextInt(5)) {
						case 0 -> String.valueOf(random.nextInt(1000));
						case 1 -> "-3.14";
						case 2 -> "1.2e-3";
						case 3 -> ".5";
						default -> "+7";
					})
					.append(" .\n");
			case 6 -> rdf.append("ex:s").append(index).append(" ex:flag ")
					.append(random.nextBoolean()).append(" .\n");
			case 7 -> rdf.append("ex:s").append(index).append(" a ex:Type").append(index % 5).append(" .\n");
			case 8 -> rdf.append("ex:s").append(index).append(" ex:p \"typed ").append(index)
					.append("\"^^ex:dt ; ex:q \"tagged\"@en-GB .\n");
			case 9 -> rdf.append("ex:s").append(index).append(" ex:multi \"\"\"line one ").append(index)
					.append("\n\"quoted\" . }\nline three\"\"\" .\n");
			case 10 -> rdf.append("ex:s").append(index).append(" ex:p [ ex:q [ ex:r \"deep ")
					.append(index).append("\" ] ; ex:t \"shallow\" ] .\n");
			case 11 -> rdf.append("ex:s").append(index).append(" ex:list ( 1 ( \"nested\" ex:o")
					.append(index).append(" ) true ) .\n");
			case 12 -> rdf.append("ex:s").append(index % 7).append(".mid.name ex:p ex:o")
					.append(index % 7).append(".x .\n");
			case 13 -> rdf.append("ex:esc\\,").append(index).append(" ex:p ex:a\\~b")
					.append(index).append(" .\n");
			case 14 -> rdf.append("ex:s").append(index).append(" ex:p \"héllo € 😀 ")
					.append(index).append("\" ; ex:q \"escape \\u00E9 and \\U0001F600\" .\n");
			default -> rdf.append("ex:s").append(index).append(" gen:p ex:o")
					.append(index).append("; sparql:name \"generated ")
					.append(index).append("\" ; foaf:name \"foaf ").append(index).append("\" .\n");
		}
	}

	@Test
	void generatedCorruptedInputNeverViolatesRobustnessContract(@TempDir Path tempDir) throws IOException {
		// Take valid generated documents and apply one seeded corruption each: whatever comes out,
		// the chunker must terminate and either complete or throw TurtleSyntaxException — never
		// anything else. No outcome is pinned; the corruption space is too irregular for that.
		for (int i = 0; i < 100; i++) {
			long seed = 777_000L + i;
			boolean trig = (i % 2) == 1;
			String valid = trig ? generateTrig(seed) : generateTurtle(seed);
			Random random = new Random(seed ^ 0x5EED);
			byte[] corrupted = corrupt(valid.getBytes(StandardCharsets.UTF_8), random);

			Path caseDir = Files.createDirectories(tempDir.resolve("corrupt-" + i));
			Path inputFile = caseDir.resolve(trig ? "input.trig" : "input.ttl");
			Files.write(inputFile, corrupted);
			long chunkSize = CHUNK_SIZES[(int) Math.floorMod(seed, CHUNK_SIZES.length)];

			for (int readBufferSize : new int[] {4, RobustnessAssertions.DEFAULT_READ_BUFFER}) {
				Path outputDir = caseDir.resolve("chunks-" + readBufferSize);
				try {
					RobustnessAssertions.runChunkerClassified(inputFile, chunkSize, outputDir, readBufferSize);
				} catch (AssertionError e) {
					throw new AssertionError(
							"seed=" + seed + ", case=" + i + ", readBufferSize=" + readBufferSize, e);
				}
			}
		}
	}

	private static byte[] corrupt(byte[] bytes, Random random) {
		if (bytes.length == 0) {
			return bytes;
		}
		return switch (random.nextInt(8)) {
			case 0 -> deleteFirstOf(bytes, random, (byte) '.');
			case 1 -> java.util.Arrays.copyOf(bytes, 1 + random.nextInt(bytes.length));
			case 2 -> insertAt(bytes, random.nextInt(bytes.length + 1),
					random.nextBoolean() ? (byte) '{' : (byte) '}');
			case 3 -> deleteFirstOf(bytes, random, (byte) '"', (byte) '>', (byte) ')', (byte) ']');
			case 4 -> duplicateLine(bytes, random);
			case 5 -> insertAt(bytes, random.nextInt(bytes.length + 1), (byte) '@');
			case 6 -> flipByte(bytes, random);
			default -> insertAt(bytes, random.nextInt(bytes.length + 1), (byte) 0);
		};
	}

	private static byte[] deleteFirstOf(byte[] bytes, Random random, byte... targets) {
		int start = random.nextInt(bytes.length);
		for (int step = 0; step < bytes.length; step++) {
			int i = (start + step) % bytes.length;
			for (byte target : targets) {
				if (bytes[i] == target) {
					byte[] result = new byte[bytes.length - 1];
					System.arraycopy(bytes, 0, result, 0, i);
					System.arraycopy(bytes, i + 1, result, i, bytes.length - i - 1);
					return result;
				}
			}
		}
		return flipByte(bytes, random);
	}

	private static byte[] insertAt(byte[] bytes, int index, byte value) {
		byte[] result = new byte[bytes.length + 1];
		System.arraycopy(bytes, 0, result, 0, index);
		result[index] = value;
		System.arraycopy(bytes, index, result, index + 1, bytes.length - index);
		return result;
	}

	private static byte[] duplicateLine(byte[] bytes, Random random) {
		int position = random.nextInt(bytes.length);
		int lineStart = position;
		while (lineStart > 0 && bytes[lineStart - 1] != '\n') {
			lineStart--;
		}
		int lineEnd = position;
		while (lineEnd < bytes.length && bytes[lineEnd] != '\n') {
			lineEnd++;
		}
		if (lineEnd < bytes.length) {
			lineEnd++;
		}
		byte[] result = new byte[bytes.length + lineEnd - lineStart];
		System.arraycopy(bytes, 0, result, 0, lineEnd);
		System.arraycopy(bytes, lineStart, result, lineEnd, lineEnd - lineStart);
		System.arraycopy(bytes, lineEnd, result, lineEnd + lineEnd - lineStart, bytes.length - lineEnd);
		return result;
	}

	private static byte[] flipByte(byte[] bytes, Random random) {
		byte[] result = bytes.clone();
		result[random.nextInt(result.length)] = (byte) random.nextInt(256);
		return result;
	}
}
