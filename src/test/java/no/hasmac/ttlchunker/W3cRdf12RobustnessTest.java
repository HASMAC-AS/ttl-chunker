package no.hasmac.ttlchunker;

import static no.hasmac.ttlchunker.RobustnessAssertions.DEFAULT_READ_BUFFER;
import static no.hasmac.ttlchunker.RobustnessAssertions.runChunkerClassified;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The RDF 1.2 / RDF-star suites contain syntax the chunker documents as unsupported (quoted
 * triples, triple terms, annotations, reifiers, the VERSION directive). They are used purely as
 * garbage-input fuzz: every file must satisfy the robustness contract — terminate, and either
 * complete or throw TurtleSyntaxException — with no pinned outcome (the RDF 1.2 suite churns).
 */
class W3cRdf12RobustnessTest {

	@ParameterizedTest(name = "{0}")
	@MethodSource("rdf12ActionFiles")
	void rdf12FileNeverCrashesChunker(Path actionFile, @TempDir Path tempDir) {
		runChunkerClassified(actionFile, 4096, tempDir.resolve("chunks"), DEFAULT_READ_BUFFER);
		runChunkerClassified(actionFile, 1, tempDir.resolve("chunks-1"), DEFAULT_READ_BUFFER);
	}

	private static List<Path> rdf12ActionFiles() {
		Path root = W3cManifest.resolveResource("/w3c/rdf12");
		try (Stream<Path> paths = Files.walk(root)) {
			return paths
					.filter(Files::isRegularFile)
					.filter(W3cRdf12RobustnessTest::isActionFile)
					.sorted(Comparator.comparing(Path::toString))
					.toList();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static boolean isActionFile(Path path) {
		String name = path.getFileName().toString();
		if (name.equals("manifest.ttl")) {
			return false;
		}
		return name.endsWith(".ttl") || name.endsWith(".trig") || name.endsWith(".nt") || name.endsWith(".nq");
	}
}
