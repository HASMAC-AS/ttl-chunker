package no.hasmac.ttlchunker;

import java.io.OutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import sun.misc.Unsafe;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.LongSupplier;

public final class TurtleChunker {

	private static final int BUFFER_SIZE = 1024 * 1024 * 4;
	private static final int PARTIAL_BUFFER_INITIAL_SIZE = 64 * 1024;
	private static final int PREFIX_BUFFER_INITIAL_SIZE = 8 * 1024;

	private static final int DEFAULT = 0;
	private static final int PERIOD_PENDING = 1;
	private static final int IRI = 2;
	private static final int LITERAL = 3;
	private static final int MULTILINE_LITERAL = 4;
	private static final int LANG_TAG_OR_DATATYPE = 5;
	private static final int PREFIX_OR_BASE = 6;
	private static final int CONSUME_WHITESPACE = 7;
	private static final int COMMENT = 8;
	private static final int QUOTE_START = 9;
	private static final int QUOTE_START_SECOND = 10;
	private static final int BLANK_NODE_LABEL = 11;
	private static final int GRAPH_BLOCK_CLOSED = 12;
	private static final byte DEFAULT_ACTION_ORDINARY = 0;
	private static final byte DEFAULT_ACTION_WHITESPACE = 1;
	private static final byte DEFAULT_ACTION_IRI = 2;
	private static final byte DEFAULT_ACTION_COMMENT = 3;
	private static final byte DEFAULT_ACTION_OPEN_PAREN = 4;
	private static final byte DEFAULT_ACTION_CLOSE_PAREN = 5;
	private static final byte DEFAULT_ACTION_OPEN_BRACKET = 6;
	private static final byte DEFAULT_ACTION_CLOSE_BRACKET = 7;
	private static final byte DEFAULT_ACTION_OPEN_GRAPH = 8;
	private static final byte DEFAULT_ACTION_CLOSE_GRAPH = 9;
	private static final byte DEFAULT_ACTION_QUOTE = 10;
	private static final byte DEFAULT_ACTION_PERIOD = 11;
	private static final byte DEFAULT_ACTION_BACKSLASH = 12;
	private static final byte DEFAULT_ACTION_UNDERSCORE = 13;
	private static final byte DEFAULT_ACTION_DIRECTIVE = 14;
	private static final byte DEFAULT_ACTION_TOKEN_BOUNDARY = 15;

	private static final byte[] DEFAULT_BYTE_ACTIONS = defaultByteActions();

	private static final long TURTLE_WS_MASK =
			(1L << 0x09) |   // '\t'
					(1L << 0x0A) |   // '\n'
					(1L << 0x0D) |   // '\r'
					(1L << 0x20);    // ' '

	private static final byte[] CLOSE_GRAPH_BYTES = { '}', '\n' };

	private static final Unsafe UNSAFE = unsafe();
	private static final long BYTE_ARRAY_BASE_OFFSET = UNSAFE.arrayBaseOffset(byte[].class);
	private static final long BYTE_HIGH_BITS = 0x8080808080808080L;
	private static final long BYTE_LOW_BITS = 0x0101010101010101L;
	private static final long BACKSLASH_WORD = repeatedByte('\\');
	private static final long GREATER_THAN_WORD = repeatedByte('>');
	private static final long LF_WORD = repeatedByte('\n');
	private static final long CR_WORD = repeatedByte('\r');

	private static final long TOKEN_BOUNDARY_LOW =
			(1L << 0x09) |   // '\t'
					(1L << 0x0A) |   // '\n'
					(1L << 0x0D) |   // '\r'
					(1L << 0x20) |   // ' '
					(1L << 0x28) |   // '('
					(1L << 0x2C) |   // ','
					(1L << 0x3B);    // ';'

	private final InputStream in;
	private final byte[] chunkBuf;
	private final int readLimit;

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

	private int state = DEFAULT;
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

	private FastOutput currentOutput;
	private FastOutput blankNodeOutput;
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
		if (bufferSize == Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Buffer size is too large");
		}
		this.in = in;
		readLimit = bufferSize;
		chunkBuf = new byte[bufferSize + 1];
		chunkBuf[0] = ' ';
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
		try (InputStream inputStream = Files.newInputStream(inputFile)) {
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
			parseAll();
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

	private void parseAll() throws IOException {
		while (true) {
			if (bufPos >= bufLen) {
				readMoreData();
			}

			if (bufLen == 0) {
				emitLeftoverAtEof();
				return;
			}

			if (state == DEFAULT) {
				parseDefaultRun();
			} else {
				parseNonDefaultOneStep();
			}
		}
	}

	private void parseNonDefaultOneStep() throws IOException {
		int s = state;
		if (s == IRI) {
			parseIriOneStep();
		} else if (s == LITERAL) {
			parseLiteralOneStep();
		} else if (s == MULTILINE_LITERAL) {
			parseMultilineLiteralOneStep();
		} else if (s == CONSUME_WHITESPACE) {
			parseConsumeWhitespaceOneStep();
		} else if (s == COMMENT) {
			parseCommentOneStep();
		} else if (s == PERIOD_PENDING) {
			parsePeriodOneStep();
		} else if (s == BLANK_NODE_LABEL) {
			parseBlankNodeLabelOneStep();
		} else if (s == PREFIX_OR_BASE) {
			parsePrefixOrBaseOneStep();
		} else if (s == GRAPH_BLOCK_CLOSED) {
			parseGraphBlockClosedOneStep();
		} else if (s == LANG_TAG_OR_DATATYPE) {
			parseLangTagOrDatatypeOneStep();
		} else if (s == QUOTE_START) {
			parseQuoteStartOneStep();
		} else if (s == QUOTE_START_SECOND) {
			parseQuoteStartSecondOneStep();
		} else {
			throw new TurtleSyntaxException();
		}
	}

	private void parseDefaultRun() throws IOException {
		byte[] buf = chunkBuf;
		byte[] actions = DEFAULT_BYTE_ACTIONS;
		int p = bufPos;
		int len = bufLen;

		while (true) {
			int runStart = p;
			int c;
			int action;
			while ((action = actions[c = buf[p] & 0xff]) == DEFAULT_ACTION_ORDINARY) {
				p++;
			}

			if (p > runStart) {
				seenNonIgnorableInBlock = true;
				nextDefaultByteAtTokenBoundary = false;
			}

			if (p >= len) {
				bufPos = len;
				return;
			}

			boolean tokenBoundaryBeforeByte = nextDefaultByteAtTokenBoundary;
			p++;
			bufPos = p;

			if (action == DEFAULT_ACTION_WHITESPACE) {
				p = skipTurtleWhitespace(buf, p, len);
				bufPos = p;
				nextDefaultByteAtTokenBoundary = true;
			} else if (action == DEFAULT_ACTION_PERIOD) {
				seenNonIgnorableInBlock = true;
				nextDefaultByteAtTokenBoundary = false;
				if (nestingDepth == 0) {
					if (p >= len) {
						state = PERIOD_PENDING;
						return;
					}
					if (isTurtleWhitespace(buf[p] & 0xff)) {
						emitCurrentBlock();
						return;
					}
				}
			} else if (action == DEFAULT_ACTION_IRI) {
				seenNonIgnorableInBlock = true;
				nextDefaultByteAtTokenBoundary = false;
				state = IRI;
				return;
			} else if (action == DEFAULT_ACTION_QUOTE) {
				seenNonIgnorableInBlock = true;
				nextDefaultByteAtTokenBoundary = false;
				startLiteral((byte) c);
				return;
			} else if (action == DEFAULT_ACTION_TOKEN_BOUNDARY) {
				seenNonIgnorableInBlock = true;
				nextDefaultByteAtTokenBoundary = true;
			} else if (action == DEFAULT_ACTION_COMMENT) {
				nextDefaultByteAtTokenBoundary = false;
				state = COMMENT;
				return;
			} else if (action == DEFAULT_ACTION_BACKSLASH) {
				seenNonIgnorableInBlock = true;
				nextDefaultByteAtTokenBoundary = false;
				if (p < len) {
					bufPos = p + 1;
				} else {
					skipEscapedByteInDefault();
				}
			} else if (action == DEFAULT_ACTION_UNDERSCORE) {
				seenNonIgnorableInBlock = true;
				nextDefaultByteAtTokenBoundary = false;
				if (tokenBoundaryBeforeByte) {
					blankNodeLabelColonSeen = false;
					state = BLANK_NODE_LABEL;
					return;
				}
			} else if (action == DEFAULT_ACTION_DIRECTIVE) {
				if (seenNonIgnorableInBlock) {
					throw new TurtleSyntaxException();
				}
				seenNonIgnorableInBlock = true;
				currentBlockIsPrefixOrBase = true;
				nextDefaultByteAtTokenBoundary = false;
				state = PREFIX_OR_BASE;
				return;
			} else if (action == DEFAULT_ACTION_OPEN_PAREN) {
				seenNonIgnorableInBlock = true;
				pushNesting((byte) '(');
				nextDefaultByteAtTokenBoundary = true;
			} else if (action == DEFAULT_ACTION_OPEN_BRACKET) {
				seenNonIgnorableInBlock = true;
				pushNesting((byte) '[');
				nextDefaultByteAtTokenBoundary = true;
			} else if (action == DEFAULT_ACTION_CLOSE_PAREN || action == DEFAULT_ACTION_CLOSE_BRACKET) {
				seenNonIgnorableInBlock = true;
				popNesting();
				nextDefaultByteAtTokenBoundary = false;
			} else if (action == DEFAULT_ACTION_OPEN_GRAPH) {
				seenNonIgnorableInBlock = true;
				if (nestingDepth == 0) {
					startGraphBlock();
				} else {
					nextDefaultByteAtTokenBoundary = false;
				}
			} else if (action == DEFAULT_ACTION_CLOSE_GRAPH) {
				if (inGraphBlock && nestingDepth == 0) {
					if (seenNonIgnorableInBlock) {
						throw new TurtleSyntaxException();
					}
					closeGraphBlock();
					return;
				}
				seenNonIgnorableInBlock = true;
				nextDefaultByteAtTokenBoundary = false;
			}

			p = bufPos;
			len = bufLen;
		}
	}

	private void parseIriOneStep() {
		int p = findByte(chunkBuf, bufPos, bufLen, GREATER_THAN_WORD, '>');
		if (p < bufLen) {
			bufPos = p + 1;
			state = DEFAULT;
		} else {
			bufPos = p;
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

	private void parseLiteralOneStep() throws IOException {
		byte[] buf = chunkBuf;
		int p = bufPos;
		int len = bufLen;
		int delimiter = literalDelimiter & 0xff;
		long delimiterWord = repeatedByte(delimiter);
		int slashParity = consecutiveBackslashes & 1;

		while (p < len) {
			int special = findLiteralSpecial(buf, p, len, delimiterWord, delimiter);
			if (special > p) {
				slashParity = 0;
			}
			if (special >= len) {
				bufPos = len;
				consecutiveBackslashes = slashParity;
				return;
			}

			int c = buf[special] & 0xff;
			p = special + 1;
			if (c == '\\') {
				slashParity ^= 1;
			} else if (slashParity == 0) {
				bufPos = p;
				consecutiveBackslashes = 0;
				finishLiteral();
				return;
			} else {
				slashParity = 0;
			}
		}

		bufPos = p;
		consecutiveBackslashes = slashParity;
	}

	private void parseMultilineLiteralOneStep() throws IOException {
		byte[] buf = chunkBuf;
		int p = bufPos;
		int len = bufLen;
		int delimiter = literalDelimiter & 0xff;
		long delimiterWord = repeatedByte(delimiter);
		int slashParity = consecutiveBackslashes & 1;

		while (p < len) {
			int special = findLiteralSpecial(buf, p, len, delimiterWord, delimiter);
			if (special > p) {
				slashParity = 0;
			}
			if (special >= len) {
				bufPos = len;
				consecutiveBackslashes = slashParity;
				return;
			}

			int c = buf[special] & 0xff;
			p = special + 1;
			if (c == '\\') {
				slashParity ^= 1;
				continue;
			}

			if (slashParity == 0) {
				bufPos = p;
				consecutiveBackslashes = 0;
				if (checkForTripleQuote((byte) delimiter)) {
					finishLiteral();
					return;
				}
				buf = chunkBuf;
				p = bufPos;
				len = bufLen;
			} else {
				slashParity = 0;
			}
		}

		bufPos = p;
		consecutiveBackslashes = slashParity;
	}

	private void startLiteral(byte delimiter) throws IOException {
		literalDelimiter = delimiter;
		consecutiveBackslashes = 0;

		if (bufPos >= bufLen) {
			readMoreData();
		}
		if (bufPos >= bufLen || chunkBuf[bufPos] != delimiter) {
			state = LITERAL;
			return;
		}

		bufPos++;
		if (bufPos >= bufLen) {
			readMoreData();
		}
		if (bufPos >= bufLen || chunkBuf[bufPos] != delimiter) {
			finishLiteral();
			return;
		}

		bufPos++;
		state = MULTILINE_LITERAL;
	}

	private void finishLiteral() throws IOException {
		if (bufPos >= bufLen) {
			readMoreData();
		}
		if (bufPos < bufLen) {
			byte b = chunkBuf[bufPos];
			if (b == '@' || b == '^') {
				bufPos++;
			}
		}
		state = DEFAULT;
	}

	private void parseLangTagOrDatatypeOneStep() {
		byte b = chunkBuf[bufPos];
		if (b == '@' || b == '^') {
			bufPos++;
		}
		state = DEFAULT;
	}

	private void parsePrefixOrBaseOneStep() {
		int c = (chunkBuf[bufPos++] & 0xff) | 0x20;
		if (c != 'p' && c != 'b') {
			throw new TurtleSyntaxException();
		}
		state = DEFAULT;
	}

	private void parseConsumeWhitespaceOneStep() {
		int start = bufPos;
		int p = skipTurtleWhitespace(chunkBuf, start, bufLen);
		bufPos = p;
		if (p > start) {
			chunkStart = p;
			nextDefaultByteAtTokenBoundary = true;
		}
		if (p < bufLen) {
			state = DEFAULT;
		}
	}

	private void parseCommentOneStep() {
		int p = findEitherByte(chunkBuf, bufPos, bufLen, LF_WORD, '\n', CR_WORD, '\r');
		if (p < bufLen) {
			bufPos = p + 1;
			nextDefaultByteAtTokenBoundary = true;
			state = DEFAULT;
		} else {
			bufPos = p;
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
		int start = bufPos;
		int p = skipTurtleWhitespace(chunkBuf, start, bufLen);
		bufPos = p;
		if (p > start) {
			chunkStart = p;
			nextDefaultByteAtTokenBoundary = true;
		}
		if (p >= bufLen) {
			return;
		}

		byte b = chunkBuf[p];
		if (b == '.') {
			bufPos = p + 1;
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

	private void parsePeriodOneStep() throws IOException {
		int next = chunkBuf[bufPos] & 0xff;
		state = DEFAULT;
		if (isTurtleWhitespace(next)) {
			emitCurrentBlock();
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

	private void emitCurrentBlock() throws IOException {
		byte[] bytes;
		int offset;
		int end;
		boolean prefixOrBase = currentBlockIsPrefixOrBase;
		boolean blankNodeLabel = currentBlockHasBlankNodeLabel;
		byte[] graphHeader = inGraphBlock ? currentGraphHeader : null;

		if (!multiReadBlock) {
			int length = bufPos - chunkStart;
			if (length <= 0) {
				chunkStart = bufPos;
				state = CONSUME_WHITESPACE;
				resetBlockFlags();
				return;
			}
			bytes = chunkBuf;
			offset = chunkStart;
			end = chunkStart + length;
		} else {
			if (bufPos > chunkStart) {
				writePartial(chunkBuf, chunkStart, bufPos - chunkStart);
			}
			bytes = partialBytes;
			offset = 0;
			end = partialSize;
			partialBytes = new byte[PARTIAL_BUFFER_INITIAL_SIZE];
			partialSize = 0;
			multiReadBlock = false;
		}

		chunkStart = bufPos;
		offset = skipTurtleWhitespace(bytes, offset, end);
		end = trimTrailingTurtleWhitespace(bytes, offset, end);

		state = CONSUME_WHITESPACE;
		resetBlockFlags();

		int length = end - offset;
		if (length > 0) {
			writeBlock(bytes, offset, length, prefixOrBase, blankNodeLabel, graphHeader);
		}
	}


	private void emitLeftoverAtEof() throws IOException {
		if (inGraphBlock) {
			throw new TurtleSyntaxException();
		}
		if (!multiReadBlock && partialSize == 0) {
			return;
		}

		byte[] bytes = partialBytes;
		int offset = 0;
		int end = partialSize;
		boolean prefixOrBase = currentBlockIsPrefixOrBase;
		boolean blankNodeLabel = currentBlockHasBlankNodeLabel;

		partialBytes = new byte[PARTIAL_BUFFER_INITIAL_SIZE];
		partialSize = 0;
		multiReadBlock = false;
		chunkStart = 0;

		offset = skipTurtleWhitespace(bytes, offset, end);
		end = trimTrailingTurtleWhitespace(bytes, offset, end);
		resetBlockFlags();

		int length = end - offset;
		if (length > 0) {
			writeBlock(bytes, offset, length, prefixOrBase, blankNodeLabel, null);
		}
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
		if (partialSize == 0) {
			int start = skipTurtleWhitespace(chunkBuf, chunkStart, endExclusive);
			int end = trimTrailingTurtleWhitespace(chunkBuf, start, endExclusive);
			return Arrays.copyOfRange(chunkBuf, start, end);
		}

		int currentLength = endExclusive - chunkStart;
		byte[] bytes = new byte[partialSize + currentLength];
		System.arraycopy(partialBytes, 0, bytes, 0, partialSize);
		if (currentLength > 0) {
			System.arraycopy(chunkBuf, chunkStart, bytes, partialSize, currentLength);
		}

		int start = skipTurtleWhitespace(bytes, 0, bytes.length);
		int end = trimTrailingTurtleWhitespace(bytes, start, bytes.length);
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
			read = in.read(chunkBuf, 0, readLimit);
		} while (read == 0);

		bufLen = read < 0 ? 0 : read;
		chunkBuf[bufLen] = ' ';
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

	private void writeBlock(byte[] bytes, int offset, int length, boolean prefixOrBase,
	                        boolean blankNodeLabel, byte[] graphHeader) throws IOException {
		if (prefixOrBase) {
			writePrefix(bytes, offset, length);
			writePrefix('\n');
			writeDirectiveToOpenChunks(bytes, offset, length);
			return;
		}

		if (blankNodeLabel) {
			writeBlankNodeStatement(bytes, offset, length, graphHeader);
			return;
		}

		if (currentOutput == null) {
			openNextChunk();
		}

		currentChunkBytes += switchCurrentGraph(graphHeader);
		currentOutput.writeLine(bytes, offset, length);
		currentChunkBytes += length + 1L;
		statements++;

		if (currentChunkBytes > approximateChunkSizeBytes) {
			closeCurrentOutput();
		}
	}

	private void writeBlankNodeStatement(byte[] bytes, int offset, int length, byte[] graphHeader) throws IOException {
		if (blankNodeOutput == null) {
			openBlankNodeChunk();
		}

		switchBlankNodeGraph(graphHeader);
		blankNodeOutput.writeLine(bytes, offset, length);
		statements++;
	}

	private void writeDirectiveToOpenChunks(byte[] bytes, int offset, int length) throws IOException {
		if (currentOutput != null) {
			currentChunkBytes += closeCurrentGraph();
			currentOutput.writeLine(bytes, offset, length);
			currentChunkBytes += length + 1L;
			if (currentChunkBytes > approximateChunkSizeBytes) {
				closeCurrentOutput();
			}
		}

		if (blankNodeOutput != null) {
			closeBlankNodeGraph();
			blankNodeOutput.writeLine(bytes, offset, length);
		}
	}

	private void openNextChunk() throws IOException {
		chunkIndex++;
		Path chunkPath = outputDir.resolve(formatChunkFileName(chunkIndex, chunkFileExtension));
		currentChunkPath = chunkPath;
		currentChunkStartedMillis = currentTimeMillis.getAsLong();
		currentOutput = new FastOutput(Files.newOutputStream(chunkPath), BUFFER_SIZE);
		currentChunkBytes = writeChunkHeader(currentOutput);
	}

	private void openBlankNodeChunk() throws IOException {
		chunkIndex++;
		Path chunkPath = outputDir.resolve(formatChunkFileName(chunkIndex, chunkFileExtension));
		blankNodeChunkPath = chunkPath;
		blankNodeChunkStartedMillis = currentTimeMillis.getAsLong();
		blankNodeOutput = new FastOutput(Files.newOutputStream(chunkPath), BUFFER_SIZE);
		writeChunkHeader(blankNodeOutput);
	}

	private long writeChunkHeader(FastOutput output) throws IOException {
		int length = trimTrailingTurtleWhitespace(prefixBytes, 0, prefixSize);
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

		currentOutput.write(CLOSE_GRAPH_BYTES);
		currentOutputGraphHeader = null;
		return 2L;
	}

	private long closeBlankNodeGraph() throws IOException {
		if (blankNodeOutputGraphHeader == null) {
			return 0;
		}

		blankNodeOutput.write(CLOSE_GRAPH_BYTES);
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

	private static Unsafe unsafe() {
		try {
			Field field = Unsafe.class.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			return (Unsafe) field.get(null);
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private static int findByte(byte[] bytes, int offset, int end, long repeatedTarget, int target) {
		int limit = end - Long.BYTES;
		while (offset <= limit) {
			long word = UNSAFE.getLong(bytes, BYTE_ARRAY_BASE_OFFSET + offset);
			long mask = zeroByteMask(word ^ repeatedTarget);
			if (mask != 0L) {
				return offset + (Long.numberOfTrailingZeros(mask) >>> 3);
			}
			offset += Long.BYTES;
		}
		while (offset < end) {
			if ((bytes[offset] & 0xff) == target) {
				return offset;
			}
			offset++;
		}
		return end;
	}

	private static int findEitherByte(byte[] bytes, int offset, int end,
	                                  long firstWord, int first,
	                                  long secondWord, int second) {
		int limit = end - Long.BYTES;
		while (offset <= limit) {
			long word = UNSAFE.getLong(bytes, BYTE_ARRAY_BASE_OFFSET + offset);
			long mask = zeroByteMask(word ^ firstWord) | zeroByteMask(word ^ secondWord);
			if (mask != 0L) {
				return offset + (Long.numberOfTrailingZeros(mask) >>> 3);
			}
			offset += Long.BYTES;
		}
		while (offset < end) {
			int c = bytes[offset] & 0xff;
			if (c == first || c == second) {
				return offset;
			}
			offset++;
		}
		return end;
	}

	private static int findLiteralSpecial(byte[] bytes, int offset, int end,
	                                      long delimiterWord, int delimiter) {
		int limit = end - Long.BYTES;
		while (offset <= limit) {
			long word = UNSAFE.getLong(bytes, BYTE_ARRAY_BASE_OFFSET + offset);
			long mask = zeroByteMask(word ^ delimiterWord) | zeroByteMask(word ^ BACKSLASH_WORD);
			if (mask != 0L) {
				return offset + (Long.numberOfTrailingZeros(mask) >>> 3);
			}
			offset += Long.BYTES;
		}
		while (offset < end) {
			int c = bytes[offset] & 0xff;
			if (c == delimiter || c == '\\') {
				return offset;
			}
			offset++;
		}
		return end;
	}

	private static long zeroByteMask(long word) {
		return (word - BYTE_LOW_BITS) & ~word & BYTE_HIGH_BITS;
	}

	private static long repeatedByte(int b) {
		return (b & 0xffL) * BYTE_LOW_BITS;
	}

	private static byte[] defaultByteActions() {
		byte[] actions = new byte[256];

		actions[0x20] = DEFAULT_ACTION_WHITESPACE; // ' '
		actions[0x09] = DEFAULT_ACTION_WHITESPACE; // '\t'
		actions[0x0A] = DEFAULT_ACTION_WHITESPACE; // '\n'
		actions[0x0D] = DEFAULT_ACTION_WHITESPACE; // '\r'
		actions[0x2C] = DEFAULT_ACTION_TOKEN_BOUNDARY; // ','
		actions[0x3B] = DEFAULT_ACTION_TOKEN_BOUNDARY; // ';'
		actions[0x3C] = DEFAULT_ACTION_IRI; // '<'
		actions[0x23] = DEFAULT_ACTION_COMMENT; // '#'
		actions[0x28] = DEFAULT_ACTION_OPEN_PAREN; // '('
		actions[0x29] = DEFAULT_ACTION_CLOSE_PAREN; // ')'
		actions[0x5B] = DEFAULT_ACTION_OPEN_BRACKET; // '['
		actions[0x5D] = DEFAULT_ACTION_CLOSE_BRACKET; // ']'
		actions[0x7B] = DEFAULT_ACTION_OPEN_GRAPH; // '{'
		actions[0x7D] = DEFAULT_ACTION_CLOSE_GRAPH; // '}'
		actions[0x27] = DEFAULT_ACTION_QUOTE; // '\''
		actions[0x22] = DEFAULT_ACTION_QUOTE; // '"'
		actions[0x2E] = DEFAULT_ACTION_PERIOD; // '.'
		actions[0x5C] = DEFAULT_ACTION_BACKSLASH; // '\\'
		actions[0x5F] = DEFAULT_ACTION_UNDERSCORE; // '_'
		actions[0x40] = DEFAULT_ACTION_DIRECTIVE; // '@'

		return actions;
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

	private static int skipTurtleWhitespace(byte[] bytes, int offset, int end) {
		while (offset < end) {
			int c = bytes[offset] & 0xff;
			if (!isTurtleWhitespace(c)) {
				break;
			}
			offset++;
		}
		return offset;
	}

	private static int trimTrailingTurtleWhitespace(byte[] bytes, int start, int end) {
		while (end > start && isTurtleWhitespace(bytes[end - 1] & 0xff)) {
			end--;
		}
		return end;
	}

	private static int trimTrailingTurtleWhitespace(byte[] bytes, int length) {
		return trimTrailingTurtleWhitespace(bytes, 0, length);
	}

	private static boolean isTurtleWhitespace(byte b) {
		return isTurtleWhitespace(b & 0xff);
	}

	private static boolean isTurtleWhitespace(int c) {
		return c <= 0x20 && ((TURTLE_WS_MASK >>> c) & 1L) != 0;
	}

	private static boolean isTokenBoundaryAfter(byte b) {
		return isTokenBoundaryAfter(b & 0xff);
	}

	private static boolean isTokenBoundaryAfter(int c) {
		return c < 64
				? ((TOKEN_BOUNDARY_LOW >>> c) & 1L) != 0
				: c == '[';
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
		return c <= 0x20 && ((TURTLE_WS_MASK >>> c) & 1L) != 0;
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

	private static final class FastOutput implements AutoCloseable {
		private final OutputStream out;
		private final byte[] buffer;
		private int position;

		FastOutput(OutputStream out, int bufferSize) {
			if (out == null) {
				throw new NullPointerException();
			}
			this.out = out;
			this.buffer = new byte[bufferSize];
		}

		void write(int b) throws IOException {
			int p = position;
			if (p == buffer.length) {
				flushBuffer();
				p = 0;
			}
			buffer[p] = (byte) b;
			position = p + 1;
		}

		void write(byte[] source) throws IOException {
			write(source, 0, source.length);
		}

		void write(byte[] source, int offset, int length) throws IOException {
			if (length <= 0) {
				return;
			}

			byte[] buf = buffer;
			int p = position;
			int available = buf.length - p;
			if (length <= available) {
				System.arraycopy(source, offset, buf, p, length);
				position = p + length;
				return;
			}

			if (p != 0) {
				flushBuffer();
			}

			if (length >= buf.length) {
				out.write(source, offset, length);
			} else {
				System.arraycopy(source, offset, buf, 0, length);
				position = length;
			}
		}

		void writeLine(byte[] source, int offset, int length) throws IOException {
			byte[] buf = buffer;
			int p = position;
			int total = length + 1;
			if (total > 0 && total <= buf.length - p) {
				System.arraycopy(source, offset, buf, p, length);
				p += length;
				buf[p] = '\n';
				position = p + 1;
				return;
			}
			write(source, offset, length);
			write('\n');
		}

		private void flushBuffer() throws IOException {
			int p = position;
			if (p != 0) {
				out.write(buffer, 0, p);
				position = 0;
			}
		}

		@Override
		public void close() throws IOException {
			IOException failure = null;
			try {
				flushBuffer();
			} catch (IOException e) {
				failure = e;
			}
			try {
				out.close();
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
	}

	public static final class TurtleSyntaxException extends RuntimeException {
		public TurtleSyntaxException() {
			super();
		}
	}
}
