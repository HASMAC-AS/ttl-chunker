package no.hasmac.ttlchunker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

/**
 * The robustness contract for arbitrary (including malformed) input: the chunker must terminate
 * and either complete normally or throw {@link TurtleChunker.TurtleSyntaxException} — never any
 * other exception or error, and never hang.
 */
final class RobustnessAssertions {

	enum Outcome {
		COMPLETES,
		THROWS
	}

	static final int DEFAULT_READ_BUFFER = -1;

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private RobustnessAssertions() {
	}

	/**
	 * Pinned outcome for a W3C negative-syntax test, from
	 * {@code src/test/resources/w3c/expected-negative-outcomes.txt}. Returns null for an unpinned
	 * test — the caller fails with an "add this line" message so new suite entries cannot go
	 * unpinned silently. Regenerate the file with
	 * {@code mvn test -Dtest=W3cSuiteTest -Dw3c.generateExpectedOutcomes=true}.
	 */
	static Outcome expectedNegativeOutcome(String qualifiedTestName) {
		return ExpectedOutcomes.PINS.get(qualifiedTestName);
	}

	private static final class ExpectedOutcomes {
		private static final java.util.Map<String, Outcome> PINS = load();

		private static java.util.Map<String, Outcome> load() {
			Path pinsFile = W3cManifest.resolveResource("/w3c/expected-negative-outcomes.txt");
			java.util.Map<String, Outcome> pins = new java.util.HashMap<>();
			try {
				for (String line : java.nio.file.Files.readAllLines(pinsFile)) {
					if (line.isBlank() || line.startsWith("#")) {
						continue;
					}
					int tab = line.indexOf('\t');
					pins.put(line.substring(0, tab), Outcome.valueOf(line.substring(tab + 1)));
				}
			} catch (IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
			return pins;
		}
	}

	static Outcome runChunkerClassified(Path inputFile, long chunkSizeBytes, Path outputDir, int readBufferSize) {
		return assertTimeoutPreemptively(TIMEOUT, () -> {
			try {
				int chunkCount;
				if (readBufferSize == DEFAULT_READ_BUFFER) {
					chunkCount = TurtleChunker.writeChunks(inputFile, chunkSizeBytes, outputDir, false);
				} else {
					chunkCount = TurtleChunker.writeChunks(inputFile, chunkSizeBytes, outputDir, false,
							System.out, System::currentTimeMillis, readBufferSize);
				}
				assertEquals(RdfTestSupport.listRegularFiles(outputDir).size(), chunkCount,
						"Return value must match the number of chunk files written");
				return Outcome.COMPLETES;
			} catch (TurtleChunker.TurtleSyntaxException e) {
				return Outcome.THROWS;
			} catch (IOException e) {
				return fail("Robustness contract violated: unexpected IOException", e);
			} catch (Throwable t) {
				return fail("Robustness contract violated: only TurtleSyntaxException or normal completion "
						+ "is allowed, but got " + t.getClass().getName(), t);
			}
		}, "Chunker must terminate within " + TIMEOUT.toSeconds() + " seconds");
	}
}
