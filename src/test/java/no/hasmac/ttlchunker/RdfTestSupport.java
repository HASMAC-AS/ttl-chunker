package no.hasmac.ttlchunker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.util.Models;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;

final class RdfTestSupport {
	static final String BASE_URI = "http://example.com/base/";

	private RdfTestSupport() {
	}

	static Model parse(Path path, RDFFormat format) throws IOException {
		return parse(path, format, BASE_URI);
	}

	static Model parse(Path path, RDFFormat format, String baseUri) throws IOException {
		try (InputStream input = Files.newInputStream(path)) {
			return Rio.parse(input, baseUri, format);
		}
	}

	static Model parse(String rdf, RDFFormat format) throws IOException {
		return parse(rdf, format, BASE_URI);
	}

	static Model parse(String rdf, RDFFormat format, String baseUri) throws IOException {
		try (InputStream input = new ByteArrayInputStream(rdf.getBytes(StandardCharsets.UTF_8))) {
			return Rio.parse(input, baseUri, format);
		}
	}

	static List<Path> listRegularFiles(Path outputDir) throws IOException {
		if (!Files.isDirectory(outputDir)) {
			return List.of();
		}
		try (Stream<Path> paths = Files.list(outputDir)) {
			return paths
					.filter(Files::isRegularFile)
					.sorted(Comparator.comparing(path -> path.getFileName().toString()))
					.collect(Collectors.toList());
		}
	}

	static void assertEveryChunkParses(Path outputDir, RDFFormat format) throws IOException {
		assertEveryChunkParses(outputDir, format, BASE_URI);
	}

	static void assertEveryChunkParses(Path outputDir, RDFFormat format, String baseUri) throws IOException {
		for (Path chunk : listRegularFiles(outputDir)) {
			parse(chunk, format, baseUri);
		}
	}

	static void assertChunkUnionIsomorphic(Path input, Path outputDir, RDFFormat format) throws IOException {
		assertChunkUnionIsomorphic(input, outputDir, format, BASE_URI);
	}

	static void assertChunkUnionIsomorphic(Path input, Path outputDir, RDFFormat format, String baseUri)
			throws IOException {
		Model expected = parse(input, format, baseUri);
		Model actual = new LinkedHashModel();
		for (Path chunk : listRegularFiles(outputDir)) {
			actual.addAll(parse(chunk, format, baseUri));
		}
		assertEquals(expected.size(), actual.size(), "Statement count changed after chunking");
		assertTrue(Models.isomorphic(expected, actual), "Chunk union differs from original RDF model");
	}

	/**
	 * The standard round trip for a valid-syntax fixture: validate the input against RDF4J first,
	 * then chunk at several sizes plus once with a tiny read buffer, asserting after each run that
	 * every chunk parses standalone and the union is isomorphic to the original.
	 */
	static Path assertChunkingRoundTrips(Path tempDir, String fileName, String input) throws IOException {
		RDFFormat format = fileName.endsWith(".trig") ? RDFFormat.TRIG : RDFFormat.TURTLE;
		assertDoesNotThrow(() -> parse(input, format), "Input must be valid " + format.getName());

		Path inputFile = tempDir.resolve(fileName);
		Files.writeString(inputFile, input, StandardCharsets.UTF_8);

		for (long chunkSize : new long[] {1, 32, 4096}) {
			Path outputDir = tempDir.resolve("chunks-" + chunkSize);
			int chunkCount = TurtleChunker.writeChunks(inputFile, chunkSize, outputDir, false);
			assertEquals(listRegularFiles(outputDir).size(), chunkCount);
			assertEveryChunkParses(outputDir, format);
			assertChunkUnionIsomorphic(inputFile, outputDir, format);
		}

		Path smallReadOutputDir = tempDir.resolve("chunks-small-read");
		TurtleChunker.writeChunks(inputFile, 1, smallReadOutputDir, false,
				System.out, System::currentTimeMillis, 5);
		assertEveryChunkParses(smallReadOutputDir, format);
		assertChunkUnionIsomorphic(inputFile, smallReadOutputDir, format);
		return inputFile;
	}

	static String turtleRichInput() {
		return """
				@prefix ex: <http://example.com/> .
				@prefix foaf: <http://xmlns.com/foaf/0.1/> .
				@base <http://example.com/base/> .
				# comment with . and } ignored
				ex:s1 ex:p "literal with . and { brace }" ;
				      ex:q <http://example.com/has%7Bbrace%7D> ;
				      foaf:name "Name"@en .
				<relativeSubject> ex:list ( "one" "two" ex:item ) ;
				                  ex:many ex:o1, ex:o2 ;
				                  ex:multi '''line one
				line two . } still literal''' .
				_:shared ex:p "blank subject" .
				ex:usesBlank ex:p _:shared .
				""";
	}

	static String trigRichInput() {
		return """
				@prefix ex: <http://example.com/> .
				{
				  ex:default ex:p "default" .
				}
				ex:g1 {
				  ex:named ex:p "named" .
				  ex:named ex:list ( ex:a ex:b ) .
				}
				GRAPH ex:g2 {
				  _:shared ex:p "one" .
				  ex:named ex:link _:shared .
				}
				ex:top ex:p "top-level default" .
				""";
	}
}
