package no.hasmac.ttlchunker;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.LongSupplier;

public final class TurtleChunker {

	private static final int BUFFER_SIZE = 1024 * 1024 * 4;
	private static final int PARTIAL_BUFFER_INITIAL_SIZE = 64 * 1024;
	private static final int PREFIX_BUFFER_INITIAL_SIZE = 8 * 1024;

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
	private static final byte BLANK_NODE_LABEL = 11;
	private static final byte GRAPH_BLOCK_CLOSED = 12;
	private static final boolean[] TOKEN_BOUNDARY_AFTER = tokenBoundaryAfterTable();

	private final InputStream in;
	private final byte[] chunkBuf;

	private byte[] partialBytes = new byte[PARTIAL_BUFFER_INITIAL_SIZE];
	private int partialSize;
	private int bufPos;
	private int bufLen;
	private int chunkStart;

	private boolean multiReadBlock;
	private boolean currentBlockIsPrefixOrBase;
	private boolean currentBlockHasBlankNodeLabel;
	private boolean seenNonIgnorableInBlock;
	private boolean nextDefaultByteAtTokenBoundary = true;
	private boolean inGraphBlock;

	private byte state = DEFAULT;
	private byte literalDelimiter;
	private int consecutiveBackslashes;
	private boolean blankNodeLabelColonSeen;
	private byte[] currentGraphHeader;

	private byte[] nestingStack = new byte[32];
	private int nestingDepth;

	private byte[] pendingBytes;
	private int pendingOffset;
	private int pendingLength;
	private boolean pendingPrefixOrBase;
	private boolean pendingBlankNodeLabel;
	private byte[] pendingGraphHeader;

	private byte[] blockBytes;
	private int blockOffset;
	private int blockLength;
	private boolean blockPrefixOrBase;
	private boolean blockBlankNodeLabel;
	private byte[] blockGraphHeader;

	private Path outputDir;
	private long approximateChunkSizeBytes;
	private boolean printStatus;
	private PrintStream statusOutput;
	private LongSupplier currentTimeMillis;
	private String chunkFileExtension;
	private byte[] prefixBytes = new byte[PREFIX_BUFFER_INITIAL_SIZE];
	private int prefixSize;

	private OutputStream currentOutput;
	private OutputStream blankNodeOutput;
	private Path currentChunkPath;
	private Path blankNodeChunkPath;
	private long currentChunkStartedMillis;
	private long blankNodeChunkStartedMillis;
	private byte[] currentOutputGraphHeader;
	private byte[] blankNodeOutputGraphHeader;
	private long currentChunkBytes;
	private long statements;
	private int chunkIndex;

	private TurtleChunker(InputStream in) {
		this(in, BUFFER_SIZE);
	}

	private TurtleChunker(InputStream in, int bufferSize) {
		if (in == null) {
			throw new NullPointerException();
		}
		if (bufferSize <= 0) {
			throw new IllegalArgumentException("Buffer size must be greater than zero");
		}
		this.in = in;
		chunkBuf = new byte[bufferSize];
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
		return writeChunks(inputFile, approximateChunkSizeBytes, outputDir, printStatus, statusOutput,
				currentTimeMillis, BUFFER_SIZE);
	}

	static int writeChunks(Path inputFile, long approximateChunkSizeBytes, Path outputDir, boolean printStatus,
			PrintStream statusOutput, LongSupplier currentTimeMillis, int readBufferSize) throws IOException {
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

		int chunkCount;
		long statementCount;
		try (InputStream inputStream = new BufferedInputStream(Files.newInputStream(inputFile), BUFFER_SIZE)) {
			TurtleChunker chunker = new TurtleChunker(inputStream, readBufferSize);
			chunker.writeChunksTo(outputDir, approximateChunkSizeBytes, printStatus, statusOutput,
					currentTimeMillis, chunkFileExtension(inputFile));
			chunkCount = chunker.chunkIndex;
			statementCount = chunker.statements;
		}

		if (printStatus) {
			statusOutput.print("Wrote ");
			statusOutput.print(statementCount);
			statusOutput.print(" statements into ");
			statusOutput.print(chunkCount);
			statusOutput.print(" chunk file(s): ");
			statusOutput.println(outputDir.toAbsolutePath());
		}

		return chunkCount;
	}

	private void writeChunksTo(Path outputDir, long approximateChunkSizeBytes, boolean printStatus,
			PrintStream statusOutput, LongSupplier currentTimeMillis, String chunkFileExtension) throws IOException {
		this.outputDir = outputDir;
		this.approximateChunkSizeBytes = approximateChunkSizeBytes;
		this.printStatus = printStatus;
		this.statusOutput = statusOutput;
		this.currentTimeMillis = currentTimeMillis;
		this.chunkFileExtension = chunkFileExtension;

		IOException failure = null;
		try {
			while (nextBlock()) {
				writeBlock();
				clearBlock();
			}
		} catch (IOException e) {
			failure = e;
		} finally {
			try {
				closeOutputs();
			} catch (IOException e) {
				if (failure == null) {
					failure = e;
				} else {
					failure.addSuppressed(e);
				}
			}
		}
		if (failure != null) {
			throw failure;
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

	private boolean nextBlock() throws IOException {
		while (true) {
			if (bufPos >= bufLen) {
				readMoreData();
			}

			if (bufLen == 0) {
				return emitLeftoverAtEof();
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
				case BLANK_NODE_LABEL -> parseBlankNodeLabelOneStep();
				case GRAPH_BLOCK_CLOSED -> parseGraphBlockClosedOneStep();
				default -> throw new TurtleSyntaxException();
			}

			if (pendingBytes != null && emitPending()) {
				return true;
			}
		}
	}

	private void parseDefaultOneStep() throws IOException {
		byte b = chunkBuf[bufPos++];
		boolean tokenBoundaryBeforeByte = nextDefaultByteAtTokenBoundary;

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
			case '{' -> {
				seenNonIgnorableInBlock = true;
				if (nestingDepth == 0) {
					startGraphBlock();
					return;
				}
			}
			case '}' -> {
				if (inGraphBlock && nestingDepth == 0) {
					if (seenNonIgnorableInBlock) {
						throw new TurtleSyntaxException();
					}
					closeGraphBlock();
					return;
				}
				seenNonIgnorableInBlock = true;
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
			case '_' -> {
				seenNonIgnorableInBlock = true;
				if (tokenBoundaryBeforeByte) {
					blankNodeLabelColonSeen = false;
					state = BLANK_NODE_LABEL;
				}
			}
			case '@' -> {
				if (seenNonIgnorableInBlock) {
					throw new TurtleSyntaxException();
				}
				seenNonIgnorableInBlock = true;
				currentBlockIsPrefixOrBase = true;
				state = PREFIX_OR_BASE;
			}
			case ' ', '\t', '\n', '\r' -> {
			}
			default -> seenNonIgnorableInBlock = true;
		}

		nextDefaultByteAtTokenBoundary = isTokenBoundaryAfter(b);
	}

	private void parseIriOneStep() {
		while (bufPos < bufLen) {
			if (chunkBuf[bufPos++] == '>') {
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
			byte b = chunkBuf[bufPos++];
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
			byte b = chunkBuf[bufPos++];
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
		byte b = chunkBuf[bufPos++];
		if (b != 'p' && b != 'P' && b != 'b' && b != 'B') {
			throw new TurtleSyntaxException();
		}
		state = DEFAULT;
	}

	private void parseConsumeWhitespaceOneStep() {
		if (isTurtleWhitespace(chunkBuf[bufPos])) {
			bufPos++;
			chunkStart++;
			nextDefaultByteAtTokenBoundary = true;
		} else {
			state = DEFAULT;
		}
	}

	private void parseCommentOneStep() {
		while (bufPos < bufLen) {
			byte b = chunkBuf[bufPos++];
			if (b == '\n' || b == '\r') {
				nextDefaultByteAtTokenBoundary = true;
				state = DEFAULT;
				return;
			}
		}
	}

	private void parseBlankNodeLabelOneStep() {
		byte b = chunkBuf[bufPos++];
		if (!blankNodeLabelColonSeen) {
			if (b == ':') {
				blankNodeLabelColonSeen = true;
				nextDefaultByteAtTokenBoundary = false;
			} else {
				nextDefaultByteAtTokenBoundary = isTokenBoundaryAfter(b);
				state = DEFAULT;
			}
			return;
		}

		if (isBlankNodeLabelFirstChar(b)) {
			currentBlockHasBlankNodeLabel = true;
		}
		blankNodeLabelColonSeen = false;
		nextDefaultByteAtTokenBoundary = isTokenBoundaryAfter(b);
		state = DEFAULT;
	}

	private void parseGraphBlockClosedOneStep() {
		byte b = chunkBuf[bufPos];
		if (isTurtleWhitespace(b)) {
			bufPos++;
			chunkStart++;
			nextDefaultByteAtTokenBoundary = true;
		} else if (b == '.') {
			bufPos++;
			chunkStart++;
			state = CONSUME_WHITESPACE;
		} else {
			state = DEFAULT;
		}
	}

	private void startGraphBlock() {
		if (inGraphBlock) {
			throw new TurtleSyntaxException();
		}
		currentGraphHeader = copyCurrentBlockTrimmed(bufPos);
		inGraphBlock = true;
		clearCurrentBlockBytes();
		chunkStart = bufPos;
		resetBlockFlags();
		state = DEFAULT;
	}

	private void closeGraphBlock() {
		inGraphBlock = false;
		currentGraphHeader = null;
		clearCurrentBlockBytes();
		chunkStart = bufPos;
		resetBlockFlags();
		state = GRAPH_BLOCK_CLOSED;
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
				writePartial(chunkBuf, chunkStart, bufPos - chunkStart);
			}
			pendingLength = partialSize;
			pendingBytes = detachPartialBytes();
			pendingOffset = 0;
			multiReadBlock = false;
		}

		pendingPrefixOrBase = currentBlockIsPrefixOrBase;
		pendingBlankNodeLabel = currentBlockHasBlankNodeLabel;
		pendingGraphHeader = inGraphBlock ? currentGraphHeader : null;
		chunkStart = bufPos;
	}

	private boolean emitPending() {
		byte[] bytes = pendingBytes;
		int offset = pendingOffset;
		int end = offset + pendingLength;
		boolean prefixOrBase = pendingPrefixOrBase;
		boolean blankNodeLabel = pendingBlankNodeLabel;
		byte[] graphHeader = pendingGraphHeader;

		pendingBytes = null;
		pendingOffset = 0;
		pendingLength = 0;
		pendingPrefixOrBase = false;
		pendingBlankNodeLabel = false;
		pendingGraphHeader = null;

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

		blockBytes = bytes;
		blockOffset = offset;
		blockLength = length;
		blockPrefixOrBase = prefixOrBase;
		blockBlankNodeLabel = blankNodeLabel;
		blockGraphHeader = graphHeader;
		return true;
	}

	private boolean emitLeftoverAtEof() {
		if (inGraphBlock) {
			throw new TurtleSyntaxException();
		}
		if (!multiReadBlock && partialSize == 0) {
			return false;
		}

		pendingLength = partialSize;
		pendingBytes = detachPartialBytes();
		pendingOffset = 0;
		pendingPrefixOrBase = currentBlockIsPrefixOrBase;
		pendingBlankNodeLabel = currentBlockHasBlankNodeLabel;
		pendingGraphHeader = null;
		multiReadBlock = false;
		chunkStart = 0;

		return emitPending();
	}

	private void resetBlockFlags() {
		currentBlockIsPrefixOrBase = false;
		currentBlockHasBlankNodeLabel = false;
		seenNonIgnorableInBlock = false;
		nextDefaultByteAtTokenBoundary = true;
		blankNodeLabelColonSeen = false;
		consecutiveBackslashes = 0;
		literalDelimiter = 0;
		nestingDepth = 0;
	}

	private byte[] copyCurrentBlockTrimmed(int endExclusive) {
		int currentLength = endExclusive - chunkStart;
		byte[] bytes = new byte[partialSize + currentLength];
		if (partialSize > 0) {
			System.arraycopy(partialBytes, 0, bytes, 0, partialSize);
		}
		if (currentLength > 0) {
			System.arraycopy(chunkBuf, chunkStart, bytes, partialSize, currentLength);
		}

		int start = 0;
		int end = bytes.length;
		while (start < end && isTurtleWhitespace(bytes[start])) {
			start++;
		}
		while (end > start && isTurtleWhitespace(bytes[end - 1])) {
			end--;
		}
		return Arrays.copyOfRange(bytes, start, end);
	}

	private void clearCurrentBlockBytes() {
		partialSize = 0;
		multiReadBlock = false;
	}

	private void readMoreData() throws IOException {
		if (chunkStart < bufLen) {
			writePartial(chunkBuf, chunkStart, bufLen - chunkStart);
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

	private void writeBlock() throws IOException {
		if (blockPrefixOrBase) {
			writePrefix(blockBytes, blockOffset, blockLength);
			writePrefix('\n');
			writeDirectiveToOpenChunks();
			return;
		}

		if (blockLength == 0) {
			return;
		}

		if (blockBlankNodeLabel) {
			writeBlankNodeStatement();
			return;
		}

		if (currentOutput == null) {
			openNextChunk();
		}

		currentChunkBytes += switchCurrentGraph(blockGraphHeader);
		currentOutput.write(blockBytes, blockOffset, blockLength);
		currentOutput.write('\n');
		currentChunkBytes += blockLength + 1L;
		statements++;

		if (currentChunkBytes > approximateChunkSizeBytes) {
			closeCurrentOutput();
		}
	}

	private void writeBlankNodeStatement() throws IOException {
		if (blankNodeOutput == null) {
			openBlankNodeChunk();
		}

		switchBlankNodeGraph(blockGraphHeader);
		blankNodeOutput.write(blockBytes, blockOffset, blockLength);
		blankNodeOutput.write('\n');
		statements++;
	}

	private void writeDirectiveToOpenChunks() throws IOException {
		if (currentOutput != null) {
			currentChunkBytes += closeCurrentGraph();
			currentOutput.write(blockBytes, blockOffset, blockLength);
			currentOutput.write('\n');
			currentChunkBytes += blockLength + 1L;
			if (currentChunkBytes > approximateChunkSizeBytes) {
				closeCurrentOutput();
			}
		}

		if (blankNodeOutput != null) {
			closeBlankNodeGraph();
			blankNodeOutput.write(blockBytes, blockOffset, blockLength);
			blankNodeOutput.write('\n');
		}
	}

	private void openNextChunk() throws IOException {
		chunkIndex++;
		Path chunkPath = outputDir.resolve(formatChunkFileName(chunkIndex, chunkFileExtension));
		currentChunkPath = chunkPath;
		currentChunkStartedMillis = currentTimeMillis.getAsLong();
		currentOutput = new BufferedOutputStream(Files.newOutputStream(chunkPath), BUFFER_SIZE);
		currentChunkBytes = writeChunkHeader(currentOutput);
	}

	private void openBlankNodeChunk() throws IOException {
		chunkIndex++;
		Path chunkPath = outputDir.resolve(formatChunkFileName(chunkIndex, chunkFileExtension));
		blankNodeChunkPath = chunkPath;
		blankNodeChunkStartedMillis = currentTimeMillis.getAsLong();
		blankNodeOutput = new BufferedOutputStream(Files.newOutputStream(chunkPath), BUFFER_SIZE);
		writeChunkHeader(blankNodeOutput);
	}

	private long writeChunkHeader(OutputStream output) throws IOException {
		int length = trimTrailingTurtleWhitespace(prefixBytes, prefixSize);
		if (length == 0) {
			return 0;
		}

		output.write(prefixBytes, 0, length);
		output.write('\n');
		output.write('\n');
		return length + 2L;
	}

	private void closeCurrentOutput() throws IOException {
		if (currentOutput != null) {
			currentChunkBytes += closeCurrentGraph();
			currentOutput.close();
			logChunk(currentChunkPath, currentChunkStartedMillis);
			currentOutput = null;
			currentChunkPath = null;
			currentChunkBytes = 0;
		}
	}

	private void closeBlankNodeOutput() throws IOException {
		if (blankNodeOutput != null) {
			closeBlankNodeGraph();
			blankNodeOutput.close();
			logChunk(blankNodeChunkPath, blankNodeChunkStartedMillis);
			blankNodeOutput = null;
			blankNodeChunkPath = null;
		}
	}

	private void closeOutputs() throws IOException {
		IOException failure = null;
		try {
			closeCurrentOutput();
		} catch (IOException e) {
			failure = e;
		}
		try {
			closeBlankNodeOutput();
		} catch (IOException e) {
			if (failure == null) {
				failure = e;
			} else {
				failure.addSuppressed(e);
			}
		}
		if (failure != null) {
			throw failure;
		}
	}

	private long switchCurrentGraph(byte[] graphHeader) throws IOException {
		if (Arrays.equals(currentOutputGraphHeader, graphHeader)) {
			return 0;
		}

		long written = closeCurrentGraph();
		if (graphHeader != null) {
			currentOutput.write(graphHeader);
			currentOutput.write('\n');
			currentOutputGraphHeader = graphHeader;
			written += graphHeader.length + 1L;
		}
		return written;
	}

	private long switchBlankNodeGraph(byte[] graphHeader) throws IOException {
		if (Arrays.equals(blankNodeOutputGraphHeader, graphHeader)) {
			return 0;
		}

		long written = closeBlankNodeGraph();
		if (graphHeader != null) {
			blankNodeOutput.write(graphHeader);
			blankNodeOutput.write('\n');
			blankNodeOutputGraphHeader = graphHeader;
			written += graphHeader.length + 1L;
		}
		return written;
	}

	private long closeCurrentGraph() throws IOException {
		if (currentOutputGraphHeader == null) {
			return 0;
		}

		currentOutput.write('}');
		currentOutput.write('\n');
		currentOutputGraphHeader = null;
		return 2L;
	}

	private long closeBlankNodeGraph() throws IOException {
		if (blankNodeOutputGraphHeader == null) {
			return 0;
		}

		blankNodeOutput.write('}');
		blankNodeOutput.write('\n');
		blankNodeOutputGraphHeader = null;
		return 2L;
	}

	private void logChunk(Path chunkPath, long startedMillis) {
		if (!printStatus) {
			return;
		}

		long elapsedMillis = Math.max(0L, currentTimeMillis.getAsLong() - startedMillis);
		statusOutput.print("Wrote chunk ");
		statusOutput.print(chunkPath.getFileName());
		statusOutput.print(" in ");
		printSeconds(statusOutput, elapsedMillis);
		statusOutput.println(" seconds");
	}

	private void clearBlock() {
		blockBytes = null;
		blockOffset = 0;
		blockLength = 0;
		blockPrefixOrBase = false;
		blockBlankNodeLabel = false;
		blockGraphHeader = null;
	}

	private void writePartial(byte[] source, int offset, int length) {
		if (length <= 0) {
			return;
		}
		partialBytes = ensureCapacity(partialBytes, partialSize + length);
		System.arraycopy(source, offset, partialBytes, partialSize, length);
		partialSize += length;
	}

	private byte[] detachPartialBytes() {
		byte[] detached = partialBytes;
		partialBytes = new byte[PARTIAL_BUFFER_INITIAL_SIZE];
		partialSize = 0;
		return detached;
	}

	private void writePrefix(int b) {
		prefixBytes = ensureCapacity(prefixBytes, prefixSize + 1);
		prefixBytes[prefixSize++] = (byte) b;
	}

	private void writePrefix(byte[] source, int offset, int length) {
		if (length <= 0) {
			return;
		}
		prefixBytes = ensureCapacity(prefixBytes, prefixSize + length);
		System.arraycopy(source, offset, prefixBytes, prefixSize, length);
		prefixSize += length;
	}

	private static byte[] ensureCapacity(byte[] bytes, int minimumCapacity) {
		if (minimumCapacity <= bytes.length) {
			return bytes;
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

		return Arrays.copyOf(bytes, newCapacity);
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

	private static int trimTrailingTurtleWhitespace(byte[] bytes, int length) {
		while (length > 0 && isTurtleWhitespace(bytes[length - 1])) {
			length--;
		}
		return length;
	}

	private static boolean isTurtleWhitespace(byte b) {
		return b == ' ' || b == '\t' || b == '\n' || b == '\r';
	}

	private static boolean isTokenBoundaryAfter(byte b) {
		return TOKEN_BOUNDARY_AFTER[b & 0xff];
	}

	private static boolean[] tokenBoundaryAfterTable() {
		boolean[] table = new boolean[256];
		table[' '] = true;
		table['\t'] = true;
		table['\n'] = true;
		table['\r'] = true;
		table['('] = true;
		table['['] = true;
		table[','] = true;
		table[';'] = true;
		return table;
	}

	private static boolean isBlankNodeLabelFirstChar(byte b) {
		return (b >= 'A' && b <= 'Z')
				|| (b >= 'a' && b <= 'z')
				|| (b >= '0' && b <= '9')
				|| b == '_'
				|| (b & 0x80) != 0;
	}

	private static char asciiUpper(char c) {
		return c >= 'a' && c <= 'z' ? (char) (c - ('a' - 'A')) : c;
	}

	private static boolean isAsciiBlank(char c) {
		return c == ' ' || c == '\t' || c == '\n' || c == '\r';
	}

	private static void printSeconds(PrintStream output, long elapsedMillis) {
		long seconds = elapsedMillis / 1000L;
		long millis = elapsedMillis % 1000L;
		output.print(seconds);
		output.print('.');
		if (millis < 100L) {
			output.print('0');
		}
		if (millis < 10L) {
			output.print('0');
		}
		output.print(millis);
	}

	private static String formatChunkFileName(int chunkIndex, String extension) {
		int digits = decimalDigits(chunkIndex);
		int zeroes = Math.max(0, 5 - digits);
		char[] chars = new char[6 + zeroes + digits + extension.length()];

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

		for (int i = 0; i < extension.length(); i++) {
			chars[p++] = extension.charAt(i);
		}

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
		System.err.println("Usage: java -jar target/ttl-chunker-1.0-SNAPSHOT.jar <input.ttl|input.trig> <chunk-size> [output-dir]");
		System.err.println("Chunk size examples: 50000000, 64KB, 128MB, 2GB");
	}

	public static final class TurtleSyntaxException extends RuntimeException {
		public TurtleSyntaxException() {
			super();
		}
	}
}
