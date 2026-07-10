package no.hasmac.ttlchunker;

import static no.hasmac.ttlchunker.RobustnessAssertions.DEFAULT_READ_BUFFER;
import static no.hasmac.ttlchunker.RobustnessAssertions.expectedNegativeOutcome;
import static no.hasmac.ttlchunker.RobustnessAssertions.runChunkerClassified;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import no.hasmac.ttlchunker.RobustnessAssertions.Outcome;
import no.hasmac.ttlchunker.W3cManifest.W3cTestCase;
import no.hasmac.ttlchunker.W3cManifest.W3cTestType;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFParseException;
import org.eclipse.rdf4j.rio.Rio;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runs the official W3C RDF 1.1 Turtle and TriG test suites (vendored under
 * {@code src/test/resources/w3c}, see the README there) against the chunker.
 *
 * <p>Positive-syntax and eval files must survive chunking at several sizes: every chunk parses
 * standalone with RDF4J (under the test's published base IRI) and the union of all chunks is
 * isomorphic to the original. Negative-syntax files assert the robustness contract — the chunker
 * is not a validator, so it may pass invalid input through — plus a pinned per-test outcome.
 */
class W3cSuiteTest {

	private static final long[] CHUNK_SIZES = {1, 4096};

	private static final List<W3cTestCase> CASES = loadAllCases();

	private static List<W3cTestCase> loadAllCases() {
		List<W3cTestCase> testCases = new ArrayList<>();
		testCases.addAll(W3cManifest.load("/w3c/rdf11/rdf-turtle", RDFFormat.TURTLE));
		testCases.addAll(W3cManifest.load("/w3c/rdf11/rdf-trig", RDFFormat.TRIG));
		return testCases;
	}

	@ParameterizedTest(name = "{0} (chunkSize={1})")
	@MethodSource("positiveCases")
	void w3cPositiveFileSurvivesChunking(W3cTestCase testCase, long chunkSize, @TempDir Path tempDir)
			throws IOException {
		skipIfExcluded(testCase);
		assertOracleAcceptsOriginal(testCase);

		Path outputDir = tempDir.resolve("chunks");
		TurtleChunker.writeChunks(testCase.actionFile(), chunkSize, outputDir, false);

		RdfTestSupport.assertEveryChunkParses(outputDir, testCase.format(), testCase.baseIri());
		RdfTestSupport.assertChunkUnionIsomorphic(testCase.actionFile(), outputDir, testCase.format(),
				testCase.baseIri());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("negativeCases")
	void w3cNegativeFileNeverCrashesChunker(W3cTestCase testCase, @TempDir Path tempDir) {
		skipIfExcluded(testCase);

		Outcome outcome = runChunkerClassified(testCase.actionFile(), 4096, tempDir.resolve("chunks"),
				DEFAULT_READ_BUFFER);
		// Robustness only (no pin) at chunk size 1 as well: rotation must not change the contract.
		runChunkerClassified(testCase.actionFile(), 1, tempDir.resolve("chunks-1"), DEFAULT_READ_BUFFER);

		Outcome expected = expectedNegativeOutcome(testCase.qualifiedName());
		if (expected == null) {
			fail("No pinned outcome for " + testCase.qualifiedName() + " (" + testCase.comment() + "). "
					+ "Add this line to src/test/resources/w3c/expected-negative-outcomes.txt:\n"
					+ testCase.qualifiedName() + "\t" + outcome);
		}
		assertEquals(expected, outcome,
				() -> "Behavior changed for " + testCase.qualifiedName() + " (" + testCase.comment() + "). "
						+ "If intentional, update src/test/resources/w3c/expected-negative-outcomes.txt: "
						+ testCase.qualifiedName() + "\t" + outcome);
	}

	@Test
	@EnabledIfSystemProperty(named = "w3c.generateExpectedOutcomes", matches = "true")
	void generateExpectedNegativeOutcomes(@TempDir Path tempDir) throws IOException {
		List<String> lines = new ArrayList<>();
		for (W3cTestCase testCase : CASES) {
			if (!isNegative(testCase) || testCase.exclusionReason() != null) {
				continue;
			}
			Outcome outcome = runChunkerClassified(testCase.actionFile(), 4096,
					tempDir.resolve(testCase.qualifiedName()), DEFAULT_READ_BUFFER);
			lines.add(testCase.qualifiedName() + "\t" + outcome);
		}
		lines.sort(String::compareTo);

		Path pinsFile = Path.of("src/test/resources/w3c/expected-negative-outcomes.txt");
		List<String> content = new ArrayList<>(List.of(
				"# Pinned chunker outcomes for W3C negative-syntax tests, one per line:",
				"# <suite>/<test name><TAB>COMPLETES|THROWS",
				"# The chunker is not a validator, so most invalid files pass through (COMPLETES); THROWS marks",
				"# the inputs its scanner rejects. Regenerate after behavior changes with:",
				"#   mvn test -Dtest=W3cSuiteTest -Dw3c.generateExpectedOutcomes=true"));
		content.addAll(lines);
		Files.write(pinsFile, content, StandardCharsets.UTF_8);
	}

	@Test
	@EnabledIfSystemProperty(named = "w3c.debugFailures", matches = "true")
	void printFailingPositiveCases(@TempDir Path tempDir) {
		int index = 0;
		for (Arguments args : positiveCases().toList()) {
			index++;
			W3cTestCase testCase = (W3cTestCase) args.get()[0];
			long chunkSize = (long) args.get()[1];
			try {
				Path outputDir = tempDir.resolve("chunks-" + index);
				TurtleChunker.writeChunks(testCase.actionFile(), chunkSize, outputDir, false);
				RdfTestSupport.assertEveryChunkParses(outputDir, testCase.format(), testCase.baseIri());
				RdfTestSupport.assertChunkUnionIsomorphic(testCase.actionFile(), outputDir, testCase.format(),
						testCase.baseIri());
			} catch (Throwable t) {
				System.out.println("[" + index + "] " + testCase.qualifiedName() + " (chunkSize=" + chunkSize
						+ "): " + t);
			}
		}
	}

	private static Stream<Arguments> positiveCases() {
		return CASES.stream()
				.filter(testCase -> testCase.type().positive())
				.flatMap(testCase -> LongStream.of(CHUNK_SIZES)
						.mapToObj(chunkSize -> Arguments.of(testCase, chunkSize)));
	}

	private static Stream<Arguments> negativeCases() {
		return CASES.stream()
				.filter(W3cSuiteTest::isNegative)
				.map(Arguments::of);
	}

	private static boolean isNegative(W3cTestCase testCase) {
		return testCase.type() == W3cTestType.NEGATIVE_SYNTAX || testCase.type() == W3cTestType.NEGATIVE_EVAL;
	}

	private static void skipIfExcluded(W3cTestCase testCase) {
		Assumptions.assumeTrue(testCase.exclusionReason() == null,
				() -> "excluded: " + testCase.exclusionReason());
	}

	private static void assertOracleAcceptsOriginal(W3cTestCase testCase) throws IOException {
		try (InputStream input = Files.newInputStream(testCase.actionFile())) {
			Rio.parse(input, testCase.baseIri(), testCase.format());
		} catch (RDFParseException e) {
			fail("ORACLE rejects original input for " + testCase.qualifiedName()
					+ " — add to exclusions.txt with reason 'ORACLE: …' if this is an RDF4J gap", e);
		}
	}
}
