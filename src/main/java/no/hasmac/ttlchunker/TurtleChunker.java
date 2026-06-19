package no.hasmac.ttlchunker;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TurtleChunker {

	private static final int BUFFER_SIZE = 1024 * 1024 * 4;
	private static final int PREFIX_BUFFER_INITIAL_SIZE = 8 * 1024;
	private static final int PARTIAL_BUFFER_INITIAL_SIZE = 64 * 1024;

	private static final byte DEFAULT = 0;
	private static final byte PERIOD_PENDING = 1;
	private static final byte IRI = 2;
	private static final byte LITERAL = 3;
	private static final byte MULTILINE_LITERAL = 4;
	private static final byte LANG_TAG_OR_DATATYPE = 5;
	private static final byte PREFIX_OR_BASE = 6;
	private static final byte CONSUME_WHITESPACE = 7;
	private static final byte COMMENT = 8;
	private static final byte QUOTE_START = 9;
	private static final byte QUOTE_START_SECOND = 10;

	private final InputStream in;
	private final byte[] chunkBuf = new byte[BUFFER_SIZE];
	private final ByteAccumulator partialBytes = new ByteAccumulator(PARTIAL_BUFFER_INITIAL_SIZE);

	private int bufPos;
	private int bufLen;
	private int chunkStart;

	private boolean multiReadBlock;
	private boolean currentBlockIsPrefixOrBase;
	private boolean seenNonIgnorableInBlock;

	private byte state = DEFAULT;
	private byte literalDelimiter;
	private int consecutiveBackslashes;

	private byte[] nestingStack = new byte[32];
	private int nestingDepth;

	private byte[] pendingBytes;
	private int pendingOffset;
	private int pendingLength;
	private boolean pendingPrefixOrBase;

	public TurtleChunker(InputStream in) {
		if (in == null) {
			throw new NullPointerException();
		}
		this.in = in;
	}

	@FunctionalInterface
	public interface BlockConsumer {
		/**
		 * The bytes are valid only during this call. Do not retain the array
		 * unless you copy it.
		 */
		void accept(byte[] bytes, int offset, int length, boolean prefixOrBase) throws IOException;
	}

	public void forEachBlock(BlockConsumer consumer) throws IOException {
		while (nextBlock(consumer)) {
			// Intentionally empty.
		}
	}

	public boolean nextBlock(BlockConsumer consumer) throws IOException {
		if (consumer == null) {
			throw new NullPointerException();
		}

		while (true) {
			if (bufPos >= bufLen) {
				readMoreData();
			}

			if (bufLen == 0) {
				return emitLeftoverAtEof(consumer);
			}

			switch (state) {
				case DEFAULT -> parseDefaultOneStep();
				case PERIOD_PENDING -> parsePeriodOneStep();
				case IRI -> parseIriOneStep();
				case LITERAL -> parseLiteralOneStep();
				case MULTILINE_LITERAL -> parseMultilineLiteralOneStep();
				case LANG_TAG_OR_DATATYPE -> parseLangTagOrDatatypeOneStep();
				case PREFIX_OR_BASE -> parsePrefixOrBaseOneStep();
				case CONSUME_WHITESPACE -> parseConsumeWhitespaceOneStep();
				case COMMENT -> parseCommentOneStep();
				case QUOTE_START -> parseQuoteStartOneStep();
				case QUOTE_START_SECOND -> parseQuoteStartSecondOneStep();
				default -> throw new TurtleSyntaxException();
			}

			if (pendingBytes != null && emitPending(consumer)) {
				return true;
			}
		}
	}

	private void parseDefaultOneStep() throws IOException {
		byte b = nextByte();

		switch (b) {
			case '<' -> {
				seenNonIgnorableInBlock = true;
				state = IRI;
			}
			case '#' -> state = COMMENT;
			case '(' -> {
				seenNonIgnorableInBlock = true;
				pushNesting((byte) '(');
			}
			case ')' -> {
				seenNonIgnorableInBlock = true;
				popNesting();
			}
			case '[' -> {
				seenNonIgnorableInBlock = true;
				pushNesting((byte) '[');
			}
			case ']' -> {
				seenNonIgnorableInBlock = true;
				popNesting();
			}
			case '\'', '"' -> {
				seenNonIgnorableInBlock = true;
				literalDelimiter = b;
				state = QUOTE_START;
			}
			case '.' -> {
				seenNonIgnorableInBlock = true;
				if (nestingDepth == 0) {
					state = PERIOD_PENDING;
				}
			}
			case '\\' -> {
				seenNonIgnorableInBlock = true;
				skipEscapedByteInDefault();
			}
			case '@' -> {
				if (seenNonIgnorableInBlock) {
					throw new TurtleSyntaxException();
				}
				seenNonIgnorableInBlock = true;
				currentBlockIsPrefixOrBase = true;
				state = PREFIX_OR_BASE;
			}
			default -> {
				if (!isTurtleWhitespace(b)) {
					seenNonIgnorableInBlock = true;
				}
			}
		}
	}

	private void parseIriOneStep() {
		while (bufPos < bufLen) {
			if (nextByte() == '>') {
				state = DEFAULT;
				return;
			}
		}
	}

	private void parseQuoteStartOneStep() {
		if (chunkBuf[bufPos] == literalDelimiter) {
			bufPos++;
			state = QUOTE_START_SECOND;
		} else {
			state = LITERAL;
		}
	}

	private void parseQuoteStartSecondOneStep() {
		if (chunkBuf[bufPos] == literalDelimiter) {
			bufPos++;
			state = MULTILINE_LITERAL;
		} else {
			state = LANG_TAG_OR_DATATYPE;
		}
	}

	private void parseLiteralOneStep() {
		while (bufPos < bufLen) {
			byte b = nextByte();
			if (b == '\\') {
				consecutiveBackslashes++;
				continue;
			}

			boolean escaped = (consecutiveBackslashes & 1) != 0;
			consecutiveBackslashes = 0;

			if (b == literalDelimiter && !escaped) {
				state = LANG_TAG_OR_DATATYPE;
				return;
			}
		}
	}

	private void parseMultilineLiteralOneStep() throws IOException {
		while (bufPos < bufLen) {
			byte b = nextByte();
			if (b == '\\') {
				consecutiveBackslashes++;
				continue;
			}

			boolean escaped = (consecutiveBackslashes & 1) != 0;
			consecutiveBackslashes = 0;

			if (b == literalDelimiter && !escaped && checkForTripleQuote(literalDelimiter)) {
				state = LANG_TAG_OR_DATATYPE;
				return;
			}
		}
	}

	private void parseLangTagOrDatatypeOneStep() {
		byte b = chunkBuf[bufPos];

		if (b == '@') {
			bufPos++;
			state = DEFAULT;
		} else if (b == '^') {
			bufPos++;
			state = DEFAULT;
		} else {
			state = DEFAULT;
		}
	}

	private void parsePrefixOrBaseOneStep() {
		byte b = nextByte();
		if (b != 'p' && b != 'P' && b != 'b' && b != 'B') {
			throw new TurtleSyntaxException();
		}
		state = DEFAULT;
	}

	private void parseConsumeWhitespaceOneStep() {
		if (isTurtleWhitespace(chunkBuf[bufPos])) {
			bufPos++;
			chunkStart++;
		} else {
			state = DEFAULT;
		}
	}

	private void parseCommentOneStep() {
		while (bufPos < bufLen) {
			byte b = nextByte();
			if (b == '\n' || b == '\r') {
				state = DEFAULT;
				return;
			}
		}
	}

	private void parsePeriodOneStep() {
		byte next = chunkBuf[bufPos];
		state = DEFAULT;

		if (isTurtleWhitespace(next)) {
			finalizeBlock();
		}
	}

	private void skipEscapedByteInDefault() throws IOException {
		if (bufPos >= bufLen) {
			readMoreData();
		}
		if (bufPos < bufLen) {
			bufPos++;
		}
	}

	private boolean checkForTripleQuote(byte quoteChar) throws IOException {
		if (bufPos >= bufLen) {
			readMoreData();
		}
		if (bufPos >= bufLen || chunkBuf[bufPos] != quoteChar) {
			return false;
		}

		bufPos++;

		if (bufPos >= bufLen) {
			readMoreData();
		}
		if (bufPos >= bufLen || chunkBuf[bufPos] != quoteChar) {
			return false;
		}

		bufPos++;
		return true;
	}

	private void finalizeBlock() {
		if (!multiReadBlock) {
			int length = bufPos - chunkStart;
			if (length <= 0) {
				return;
			}
			pendingBytes = chunkBuf;
			pendingOffset = chunkStart;
			pendingLength = length;
		} else {
			if (bufPos > chunkStart) {
				partialBytes.write(chunkBuf, chunkStart, bufPos - chunkStart);
			}
			pendingLength = partialBytes.size();
			pendingBytes = partialBytes.detachBytes();
			pendingOffset = 0;
			multiReadBlock = false;
		}

		pendingPrefixOrBase = currentBlockIsPrefixOrBase;
		chunkStart = bufPos;
	}

	private boolean emitPending(BlockConsumer consumer) throws IOException {
		byte[] bytes = pendingBytes;
		int offset = pendingOffset;
		int end = offset + pendingLength;
		boolean prefixOrBase = pendingPrefixOrBase;

		pendingBytes = null;
		pendingOffset = 0;
		pendingLength = 0;
		pendingPrefixOrBase = false;

		while (offset < end && isTurtleWhitespace(bytes[offset])) {
			offset++;
		}
		while (end > offset && isTurtleWhitespace(bytes[end - 1])) {
			end--;
		}

		state = CONSUME_WHITESPACE;
		resetBlockFlags();

		int length = end - offset;
		if (length <= 0) {
			return false;
		}

		consumer.accept(bytes, offset, length, prefixOrBase);
		return true;
	}

	private boolean emitLeftoverAtEof(BlockConsumer consumer) throws IOException {
		if (!multiReadBlock && partialBytes.size() == 0) {
			return false;
		}

		pendingLength = partialBytes.size();
		pendingBytes = partialBytes.detachBytes();
		pendingOffset = 0;
		pendingPrefixOrBase = currentBlockIsPrefixOrBase;
		multiReadBlock = false;
		chunkStart = 0;

		return emitPending(consumer);
	}

	private void resetBlockFlags() {
		currentBlockIsPrefixOrBase = false;
		seenNonIgnorableInBlock = false;
		consecutiveBackslashes = 0;
		literalDelimiter = 0;
		nestingDepth = 0;
	}

	private void readMoreData() throws IOException {
		if (chunkStart < bufLen) {
			partialBytes.write(chunkBuf, chunkStart, bufLen - chunkStart);
			multiReadBlock = true;
		}

		chunkStart = 0;
		bufPos = 0;

		int read;
		do {
			read = in.read(chunkBuf);
		} while (read == 0);

		bufLen = read < 0 ? 0 : read;
	}

	private byte nextByte() {
		return chunkBuf[bufPos++];
	}

	private void pushNesting(byte b) {
		if (nestingDepth == nestingStack.length) {
			byte[] larger = new byte[nestingStack.length << 1];
			System.arraycopy(nestingStack, 0, larger, 0, nestingStack.length);
			nestingStack = larger;
		}
		nestingStack[nestingDepth++] = b;
	}

	private void popNesting() {
		if (nestingDepth != 0) {
			nestingDepth--;
		}
	}

	private static boolean isTurtleWhitespace(byte b) {
		return b == ' ' || b == '\t' || b == '\n' || b == '\r';
	}

	private static int trimTrailingTurtleWhitespace(byte[] bytes, int length) {
		while (length > 0 && isTurtleWhitespace(bytes[length - 1])) {
			length--;
		}
		return length;
	}

	public static int writeChunks(Path inputFile, long approximateChunkSizeBytes, Path outputDir) throws IOException {
		if (approximateChunkSizeBytes <= 0) {
			throw new IllegalArgumentException("Chunk size must be greater than zero");
		}
		if (!Files.isRegularFile(inputFile)) {
			throw new IOException("Input file not found: " + inputFile);
		}

		Files.createDirectories(outputDir);

		ChunkSink sink = new ChunkSink(outputDir, approximateChunkSizeBytes);
		try (InputStream inputStream = new BufferedInputStream(Files.newInputStream(inputFile), BUFFER_SIZE); sink) {
			new TurtleChunker(inputStream).forEachBlock(sink);
		}

		System.out.print("Wrote ");
		System.out.print(sink.statements());
		System.out.print(" statements into ");
		System.out.print(sink.chunkCount());
		System.out.print(" chunk file(s): ");
		System.out.println(outputDir.toAbsolutePath());

		return sink.chunkCount();
	}

	private static final class ChunkSink implements BlockConsumer, AutoCloseable {
		private final Path outputDir;
		private final long approximateChunkSizeBytes;
		private final ByteAccumulator prefixes = new ByteAccumulator(PREFIX_BUFFER_INITIAL_SIZE);

		private OutputStream currentOutput;
		private long currentChunkBytes;
		private long statements;
		private int chunkIndex;

		private ChunkSink(Path outputDir, long approximateChunkSizeBytes) {
			this.outputDir = outputDir;
			this.approximateChunkSizeBytes = approximateChunkSizeBytes;
		}

		@Override
		public void accept(byte[] bytes, int offset, int length, boolean prefixOrBase) throws IOException {
			if (prefixOrBase) {
				prefixes.write(bytes, offset, length);
				prefixes.write('\n');
				return;
			}

			if (length == 0) {
				return;
			}

			if (currentOutput == null) {
				openNextChunk();
			}

			currentOutput.write(bytes, offset, length);
			currentOutput.write('\n');
			currentChunkBytes += length + 1L;
			statements++;

			if (currentChunkBytes > approximateChunkSizeBytes) {
				closeCurrentOutput();
			}
		}

		private void openNextChunk() throws IOException {
			chunkIndex++;
			Path chunkPath = outputDir.resolve(formatChunkFileName(chunkIndex));
			currentOutput = new BufferedOutputStream(Files.newOutputStream(chunkPath), BUFFER_SIZE);
			currentChunkBytes = writeChunkHeader(currentOutput, prefixes);
		}

		private static long writeChunkHeader(OutputStream output, ByteAccumulator prefixes) throws IOException {
			int length = trimTrailingTurtleWhitespace(prefixes.array(), prefixes.size());
			if (length == 0) {
				return 0;
			}

			output.write(prefixes.array(), 0, length);
			output.write('\n');
			output.write('\n');
			return length + 2L;
		}

		private void closeCurrentOutput() throws IOException {
			if (currentOutput != null) {
				currentOutput.close();
				currentOutput = null;
				currentChunkBytes = 0;
			}
		}

		@Override
		public void close() throws IOException {
			closeCurrentOutput();
		}

		private long statements() {
			return statements;
		}

		private int chunkCount() {
			return chunkIndex;
		}
	}

	private static final class ByteAccumulator {
		private byte[] bytes;
		private int size;

		private ByteAccumulator(int initialCapacity) {
			bytes = new byte[Math.max(1, initialCapacity)];
		}

		private void write(int b) {
			ensureCapacity(size + 1);
			bytes[size++] = (byte) b;
		}

		private void write(byte[] source, int offset, int length) {
			if (length <= 0) {
				return;
			}
			ensureCapacity(size + length);
			System.arraycopy(source, offset, bytes, size, length);
			size += length;
		}

		private byte[] detachBytes() {
			byte[] detached = bytes;
			bytes = new byte[PARTIAL_BUFFER_INITIAL_SIZE];
			size = 0;
			return detached;
		}

		private byte[] array() {
			return bytes;
		}

		private int size() {
			return size;
		}

		private void ensureCapacity(int minimumCapacity) {
			if (minimumCapacity <= bytes.length) {
				return;
			}

			int newCapacity = bytes.length;
			while (newCapacity < minimumCapacity) {
				int doubled = newCapacity << 1;
				if (doubled <= 0) {
					newCapacity = minimumCapacity;
					break;
				}
				newCapacity = doubled;
			}

			byte[] larger = new byte[newCapacity];
			System.arraycopy(bytes, 0, larger, 0, size);
			bytes = larger;
		}
	}

	public static final class TurtleSyntaxException extends RuntimeException {
		public TurtleSyntaxException() {
			super();
		}
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

	private static char asciiUpper(char c) {
		return c >= 'a' && c <= 'z' ? (char) (c - ('a' - 'A')) : c;
	}

	private static boolean isAsciiBlank(char c) {
		return c == ' ' || c == '\t' || c == '\n' || c == '\r';
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

	private static boolean isBlank(String value) {
		for (int i = 0, length = value.length(); i < length; i++) {
			if (!isAsciiBlank(value.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	private static String formatChunkFileName(int chunkIndex) {
		int digits = decimalDigits(chunkIndex);
		int zeroes = Math.max(0, 5 - digits);
		char[] chars = new char[6 + zeroes + digits + 4];

		int p = 0;
		chars[p++] = 'c';
		chars[p++] = 'h';
		chars[p++] = 'u';
		chars[p++] = 'n';
		chars[p++] = 'k';
		chars[p++] = '-';

		for (int i = 0; i < zeroes; i++) {
			chars[p++] = '0';
		}

		int digitStart = p;
		int digitEnd = digitStart + digits;
		int n = chunkIndex;
		for (int i = digitEnd - 1; i >= digitStart; i--) {
			chars[i] = (char) ('0' + (n % 10));
			n /= 10;
		}
		p = digitEnd;

		chars[p++] = '.';
		chars[p++] = 't';
		chars[p++] = 't';
		chars[p] = 'l';

		return new String(chars);
	}

	private static int decimalDigits(int value) {
		int digits = 1;
		while (value >= 10) {
			value /= 10;
			digits++;
		}
		return digits;
	}

	private static void printUsage() {
		System.err.println("Usage: java -jar target/ttl-chunker-1.0-SNAPSHOT.jar <input.ttl> <chunk-size> [output-dir]");
		System.err.println("Chunk size examples: 50000000, 64KB, 128MB, 2GB");
	}
}
