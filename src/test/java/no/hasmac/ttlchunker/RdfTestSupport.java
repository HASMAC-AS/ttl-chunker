package no.hasmac.ttlchunker;

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
	private static final String BASE_URI = "http://example.com/base/";

	private RdfTestSupport() {
	}

	static Model parse(Path path, RDFFormat format) throws IOException {
		try (InputStream input = Files.newInputStream(path)) {
			return Rio.parse(input, BASE_URI, format);
		}
	}

	static Model parse(String rdf, RDFFormat format) throws IOException {
		try (InputStream input = new ByteArrayInputStream(rdf.getBytes(StandardCharsets.UTF_8))) {
			return Rio.parse(input, BASE_URI, format);
		}
	}

	static List<Path> listRegularFiles(Path outputDir) throws IOException {
		try (Stream<Path> paths = Files.list(outputDir)) {
			return paths
					.filter(Files::isRegularFile)
					.sorted(Comparator.comparing(path -> path.getFileName().toString()))
					.collect(Collectors.toList());
		}
	}

	static void assertEveryChunkParses(Path outputDir, RDFFormat format) throws IOException {
		for (Path chunk : listRegularFiles(outputDir)) {
			parse(chunk, format);
		}
	}

	static void assertChunkUnionIsomorphic(Path input, Path outputDir, RDFFormat format) throws IOException {
		Model expected = parse(input, format);
		Model actual = new LinkedHashModel();
		for (Path chunk : listRegularFiles(outputDir)) {
			actual.addAll(parse(chunk, format));
		}
		assertEquals(expected.size(), actual.size(), "Statement count changed after chunking");
		assertTrue(Models.isomorphic(expected, actual), "Chunk union differs from original RDF model");
	}
}
