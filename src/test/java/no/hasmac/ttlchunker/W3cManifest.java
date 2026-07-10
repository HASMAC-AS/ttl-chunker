package no.hasmac.ttlchunker;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.util.Models;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;

/**
 * Loads a W3C rdf-tests {@code manifest.ttl} from the vendored resources under
 * {@code src/test/resources/w3c} and yields one test case per manifest entry, in manifest order.
 * The manifest is itself Turtle, so RDF4J parses it; the per-file base IRI is taken from the
 * manifest's {@code mf:assumedTestBase}. Unknown test classes fail loudly so a re-vendored suite
 * cannot silently skip tests.
 */
final class W3cManifest {

	enum W3cTestType {
		POSITIVE_SYNTAX,
		NEGATIVE_SYNTAX,
		EVAL,
		NEGATIVE_EVAL;

		boolean positive() {
			return this == POSITIVE_SYNTAX || this == EVAL;
		}
	}

	record W3cTestCase(String suite, String name, W3cTestType type, RDFFormat format, Path actionFile,
			String baseIri, String comment, String exclusionReason) {

		String qualifiedName() {
			return suite + "/" + name;
		}

		@Override
		public String toString() {
			return qualifiedName();
		}
	}

	private static final String MF = "http://www.w3.org/2001/sw/DataAccess/tests/test-manifest#";
	private static final String RDFT = "http://www.w3.org/ns/rdftest#";
	private static final String DUMMY_BASE = "http://w3c-manifest.invalid/manifest.ttl";

	private W3cManifest() {
	}

	static List<W3cTestCase> load(String resourceDir, RDFFormat format) {
		Path manifestFile = resolveResource(resourceDir + "/manifest.ttl");
		Path suiteDir = manifestFile.getParent();
		String suite = suiteDir.getFileName().toString();
		Map<String, String> exclusions = loadExclusions();

		Model manifest;
		try (InputStream input = Files.newInputStream(manifestFile)) {
			manifest = Rio.parse(input, DUMMY_BASE, RDFFormat.TURTLE);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		IRI assumedTestBase = iri(manifest, MF + "assumedTestBase");
		if (assumedTestBase == null) {
			throw new IllegalStateException("Manifest lacks mf:assumedTestBase: " + manifestFile);
		}
		String baseIriPrefix = assumedTestBase.stringValue();

		List<W3cTestCase> testCases = new ArrayList<>();
		for (Resource entry : entriesInOrder(manifest)) {
			IRI type = (IRI) Models.getProperty(manifest, entry, RDF.TYPE)
					.orElseThrow(() -> new IllegalStateException("Manifest entry without rdf:type: " + entry));
			W3cTestType testType = mapType(type);

			String name = Models.getPropertyString(manifest, entry, org.eclipse.rdf4j.model.util.Values.iri(MF, "name"))
					.orElseThrow(() -> new IllegalStateException("Manifest entry without mf:name: " + entry));
			String comment = Models.getPropertyString(manifest, entry,
					org.eclipse.rdf4j.model.util.Values.iri("http://www.w3.org/2000/01/rdf-schema#", "comment"))
					.orElse("");
			IRI action = (IRI) Models.getProperty(manifest, entry,
					org.eclipse.rdf4j.model.util.Values.iri(MF, "action"))
					.orElseThrow(() -> new IllegalStateException("Manifest entry without mf:action: " + entry));

			String fileName = action.stringValue().substring(action.stringValue().lastIndexOf('/') + 1);
			Path actionFile = suiteDir.resolve(fileName);
			if (!Files.isRegularFile(actionFile)) {
				throw new IllegalStateException("Manifest action file missing: " + actionFile);
			}

			String exclusionReason = exclusions.get(suite + "/" + name);
			testCases.add(new W3cTestCase(suite, name, testType, format, actionFile,
					baseIriPrefix + fileName, comment, exclusionReason));
		}
		if (testCases.isEmpty()) {
			throw new IllegalStateException("No test cases loaded from " + manifestFile);
		}
		return testCases;
	}

	private static W3cTestType mapType(IRI type) {
		String local = type.stringValue();
		if (!local.startsWith(RDFT)) {
			throw new IllegalStateException("Unknown manifest test class: " + type);
		}
		local = local.substring(RDFT.length());
		return switch (local) {
			case "TestTurtlePositiveSyntax", "TestTrigPositiveSyntax" -> W3cTestType.POSITIVE_SYNTAX;
			case "TestTurtleNegativeSyntax", "TestTrigNegativeSyntax" -> W3cTestType.NEGATIVE_SYNTAX;
			case "TestTurtleEval", "TestTrigEval" -> W3cTestType.EVAL;
			case "TestTurtleNegativeEval", "TestTrigNegativeEval" -> W3cTestType.NEGATIVE_EVAL;
			default -> throw new IllegalStateException("Unknown manifest test class: " + type
					+ " — update W3cManifest for the re-vendored suite");
		};
	}

	private static List<Resource> entriesInOrder(Model manifest) {
		Value head = manifest.filter(null, org.eclipse.rdf4j.model.util.Values.iri(MF, "entries"), null)
				.objects().stream().findFirst()
				.orElseThrow(() -> new IllegalStateException("Manifest lacks mf:entries"));

		List<Resource> entries = new ArrayList<>();
		Resource node = (Resource) head;
		while (!RDF.NIL.equals(node)) {
			Value first = Models.getProperty(manifest, node, RDF.FIRST)
					.orElseThrow(() -> new IllegalStateException("Malformed mf:entries list"));
			entries.add((Resource) first);
			node = (Resource) Models.getProperty(manifest, node, RDF.REST)
					.orElseThrow(() -> new IllegalStateException("Malformed mf:entries list"));
		}
		return entries;
	}

	private static IRI iri(Model model, String predicate) {
		return model.filter(null, org.eclipse.rdf4j.model.util.Values.iri(predicate), null)
				.objects().stream()
				.filter(IRI.class::isInstance)
				.map(IRI.class::cast)
				.findFirst()
				.orElse(null);
	}

	private static Map<String, String> loadExclusions() {
		Path exclusionsFile = resolveResource("/w3c/exclusions.txt");
		Map<String, String> exclusions = new HashMap<>();
		try {
			for (String line : Files.readAllLines(exclusionsFile, StandardCharsets.UTF_8)) {
				if (line.isBlank() || line.startsWith("#")) {
					continue;
				}
				int tab = line.indexOf('\t');
				if (tab < 0) {
					throw new IllegalStateException("Malformed exclusions.txt line (expected name<TAB>reason): "
							+ line);
				}
				exclusions.put(line.substring(0, tab), line.substring(tab + 1));
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return exclusions;
	}

	static Path resolveResource(String resourcePath) {
		try {
			var url = W3cManifest.class.getResource(resourcePath);
			if (url == null) {
				throw new IllegalStateException("Missing test resource: " + resourcePath);
			}
			return Path.of(url.toURI());
		} catch (URISyntaxException e) {
			throw new IllegalStateException(e);
		}
	}
}
