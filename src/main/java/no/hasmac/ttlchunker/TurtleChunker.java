package no.hasmac.ttlchunker;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.LongSupplier;

public final class TurtleChunker {

	private static final int BUFFER_SIZE = 1024 * 1024 * 4;

	private final TurtleBlockReader reader;

	public TurtleChunker(InputStream in) {
		reader = new TurtleBlockReader(in);
	}

	@FunctionalInterface
	public interface BlockConsumer {
		/**
		 * The bytes are valid only during this call. Do not retain the array
		 * unless you copy it.
		 */
		void accept(byte[] bytes, int offset, int length, boolean prefixOrBase) throws IOException;

		default void accept(byte[] bytes, int offset, int length, boolean prefixOrBase, boolean blankNodeLabel)
				throws IOException {
			accept(bytes, offset, length, prefixOrBase);
		}

		default void accept(byte[] bytes, int offset, int length, boolean prefixOrBase, boolean blankNodeLabel,
				byte[] graphHeader) throws IOException {
			accept(bytes, offset, length, prefixOrBase, blankNodeLabel);
		}
	}

	public void forEachBlock(BlockConsumer consumer) throws IOException {
		reader.forEachBlock(consumer);
	}

	public boolean nextBlock(BlockConsumer consumer) throws IOException {
		return reader.nextBlock(consumer);
	}

	public static int writeChunks(Path inputFile, long approximateChunkSizeBytes, Path outputDir) throws IOException {
		return writeChunks(inputFile, approximateChunkSizeBytes, outputDir, true, System.out,
				System::currentTimeMillis);
	}

	public static int writeChunks(Path inputFile, long approximateChunkSizeBytes, Path outputDir, boolean printStatus)
			throws IOException {
		return writeChunks(inputFile, approximateChunkSizeBytes, outputDir, printStatus, System.out,
				System::currentTimeMillis);
	}

	static int writeChunks(Path inputFile, long approximateChunkSizeBytes, Path outputDir, boolean printStatus,
			PrintStream statusOutput, LongSupplier currentTimeMillis) throws IOException {
		if (printStatus && statusOutput == null) {
			throw new NullPointerException();
		}
		if (currentTimeMillis == null) {
			throw new NullPointerException();
		}
		if (approximateChunkSizeBytes <= 0) {
			throw new IllegalArgumentException("Chunk size must be greater than zero");
		}
		if (!Files.isRegularFile(inputFile)) {
			throw new IOException("Input file not found: " + inputFile);
		}

		Files.createDirectories(outputDir);

		ChunkSink sink = new ChunkSink(outputDir, approximateChunkSizeBytes, printStatus, statusOutput,
				currentTimeMillis, chunkFileExtension(inputFile));
		try (InputStream inputStream = new BufferedInputStream(Files.newInputStream(inputFile), BUFFER_SIZE); sink) {
			new TurtleBlockReader(inputStream).forEachBlock(sink);
		}

		if (printStatus) {
			statusOutput.print("Wrote ");
			statusOutput.print(sink.statements());
			statusOutput.print(" statements into ");
			statusOutput.print(sink.chunkCount());
			statusOutput.print(" chunk file(s): ");
			statusOutput.println(outputDir.toAbsolutePath());
		}

		return sink.chunkCount();
	}

	public static void main(String[] args) {
		if (args.length < 2 || args.length > 3) {
			printUsage();
			System.exit(1);
			return;
		}

		Path inputFile = Path.of(args[0]);
		long chunkSizeBytes;
		try {
			chunkSizeBytes = parseChunkSize(args[1]);
		} catch (IllegalArgumentException e) {
			System.err.print("Invalid chunk size: ");
			System.err.println(e.getMessage());
			printUsage();
			System.exit(1);
			return;
		}

		Path outputDir = args.length == 3 ? Path.of(args[2]) : defaultOutputDir(inputFile);

		try {
			int chunkCount = writeChunks(inputFile, chunkSizeBytes, outputDir);
			if (chunkCount == 0) {
				System.out.println("No Turtle statements found; no chunk files written.");
			}
		} catch (IOException e) {
			System.err.print("Chunking failed: ");
			System.err.println(e.getMessage());
			System.exit(1);
		}
	}

	static long parseChunkSize(String rawValue) {
		if (rawValue == null) {
			throw new IllegalArgumentException("Size is empty");
		}

		int start = 0;
		int end = rawValue.length();
		while (start < end && isAsciiBlank(rawValue.charAt(start))) {
			start++;
		}
		while (end > start && isAsciiBlank(rawValue.charAt(end - 1))) {
			end--;
		}
		if (start == end) {
			throw new IllegalArgumentException("Size is empty");
		}

		int i = start;
		long numeric = 0;
		while (i < end) {
			char c = rawValue.charAt(i);
			if (c < '0' || c > '9') {
				break;
			}
			int digit = c - '0';
			try {
				numeric = Math.addExact(Math.multiplyExact(numeric, 10L), digit);
			} catch (ArithmeticException e) {
				throw new IllegalArgumentException("Chunk size is too large", e);
			}
			i++;
		}

		if (i == start) {
			throw new IllegalArgumentException("Missing numeric size");
		}
		if (numeric <= 0) {
			throw new IllegalArgumentException("Chunk size must be greater than zero");
		}

		while (i < end && isAsciiBlank(rawValue.charAt(i))) {
			i++;
		}

		long multiplier = suffixMultiplier(rawValue, i, end);
		try {
			return Math.multiplyExact(numeric, multiplier);
		} catch (ArithmeticException e) {
			throw new IllegalArgumentException("Chunk size is too large", e);
		}
	}

	private static long suffixMultiplier(String value, int start, int end) {
		int length = end - start;
		if (length == 0 || suffixEquals(value, start, end, 'B')) {
			return 1L;
		}
		if (suffixEquals(value, start, end, 'K') || suffixEquals(value, start, end, 'K', 'B')) {
			return 1024L;
		}
		if (suffixEquals(value, start, end, 'M') || suffixEquals(value, start, end, 'M', 'B')) {
			return 1024L * 1024L;
		}
		if (suffixEquals(value, start, end, 'G') || suffixEquals(value, start, end, 'G', 'B')) {
			return 1024L * 1024L * 1024L;
		}
		throw new IllegalArgumentException("Unsupported size suffix");
	}

	private static boolean suffixEquals(String value, int start, int end, char first) {
		return end - start == 1 && asciiUpper(value.charAt(start)) == first;
	}

	private static boolean suffixEquals(String value, int start, int end, char first, char second) {
		return end - start == 2
				&& asciiUpper(value.charAt(start)) == first
				&& asciiUpper(value.charAt(start + 1)) == second;
	}

	private static Path defaultOutputDir(Path inputFile) {
		Path fileNamePath = inputFile.getFileName();
		String fileName = fileNamePath == null ? inputFile.toString() : fileNamePath.toString();
		int dotIndex = fileName.lastIndexOf('.');
		String baseName = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
		if (isBlank(baseName)) {
			baseName = "ttl";
		}
		return Path.of(baseName + "-chunks");
	}

	private static String chunkFileExtension(Path inputFile) {
		Path fileNamePath = inputFile.getFileName();
		String fileName = fileNamePath == null ? inputFile.toString() : fileNamePath.toString();
		return asciiEndsWithIgnoreCase(fileName, ".trig") ? ".trig" : ".ttl";
	}

	private static boolean asciiEndsWithIgnoreCase(String value, String suffix) {
		int offset = value.length() - suffix.length();
		if (offset < 0) {
			return false;
		}
		for (int i = 0; i < suffix.length(); i++) {
			if (asciiUpper(value.charAt(offset + i)) != asciiUpper(suffix.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	private static boolean isBlank(String value) {
		for (int i = 0, length = value.length(); i < length; i++) {
			if (!isAsciiBlank(value.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	private static char asciiUpper(char c) {
		return c >= 'a' && c <= 'z' ? (char) (c - ('a' - 'A')) : c;
	}

	private static boolean isAsciiBlank(char c) {
		return c == ' ' || c == '\t' || c == '\n' || c == '\r';
	}

	private static void printUsage() {
		System.err.println("Usage: java -jar target/ttl-chunker-1.0-SNAPSHOT.jar <input.ttl|input.trig> <chunk-size> [output-dir]");
		System.err.println("Chunk size examples: 50000000, 64KB, 128MB, 2GB");
	}

	public static final class TurtleSyntaxException extends RuntimeException {
		public TurtleSyntaxException() {
			super();
		}
	}
}
