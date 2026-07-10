package no.hasmac.ttlchunker;

import static no.hasmac.ttlchunker.RdfTestSupport.assertChunkUnionIsomorphic;
import static no.hasmac.ttlchunker.RdfTestSupport.assertEveryChunkParses;
import static no.hasmac.ttlchunker.RdfTestSupport.listRegularFiles;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TurtleChunkerOracleTest {
	private static final long[] CHUNK_SIZES = {1, 32, 128, 4096};

	@ParameterizedTest(name = "{0}, chunkSize={4}")
	@MethodSource("fixturesAndChunkSizes")
	void chunksParseAndPreserveOriginalModel(String name, String fileName, String input, RDFFormat format,
			long chunkSize, @TempDir Path tempDir) throws IOException {
		Path inputFile = tempDir.resolve(fileName);
		Files.writeString(inputFile, input, StandardCharsets.UTF_8);

		Path outputDir = tempDir.resolve(name + "-" + chunkSize);
		int chunkCount = TurtleChunker.writeChunks(inputFile, chunkSize, outputDir, false);

		assertEquals(listRegularFiles(outputDir).size(), chunkCount);
		assertEveryChunkParses(outputDir, format);
		assertChunkUnionIsomorphic(inputFile, outputDir, format);
	}

	private static Stream<Arguments> fixturesAndChunkSizes() {
		return fixtures().flatMap(fixture -> LongStream.of(CHUNK_SIZES)
				.mapToObj(chunkSize -> Arguments.of(fixture.name(), fixture.fileName(), fixture.input(),
						fixture.format(), chunkSize)));
	}

	private static Stream<Fixture> fixtures() {
		return Stream.of(
				new Fixture("turtle-rich", "input.ttl", RdfTestSupport.turtleRichInput(), RDFFormat.TURTLE),
				new Fixture("trig-rich", "input.trig", RdfTestSupport.trigRichInput(), RDFFormat.TRIG));
	}

	private record Fixture(String name, String fileName, String input, RDFFormat format) {
	}
}
