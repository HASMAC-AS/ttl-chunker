package no.hasmac.ttlchunker;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

final class TurtleBlockReader {

	private static final int BUFFER_SIZE = 1024 * 1024 * 4;
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
	private static final byte BLANK_NODE_LABEL = 11;
	private static final byte GRAPH_BLOCK_CLOSED = 12;
	private static final boolean[] TOKEN_BOUNDARY_AFTER = tokenBoundaryAfterTable();

	private final InputStream in;
	private final byte[] chunkBuf;
	private final ByteAccumulator partialBytes = new ByteAccumulator(PARTIAL_BUFFER_INITIAL_SIZE);

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

	TurtleBlockReader(InputStream in) {
		this(in, BUFFER_SIZE);
	}

	TurtleBlockReader(InputStream in, int bufferSize) {
		if (in == null) {
			throw new NullPointerException();
		}
		if (bufferSize <= 0) {
			throw new IllegalArgumentException("Buffer size must be greater than zero");
		}
		this.in = in;
		chunkBuf = new byte[bufferSize];
	}

	void forEachBlock(TurtleChunker.BlockConsumer consumer) throws IOException {
		while (nextBlock(consumer)) {
			// Intentionally empty.
		}
	}

	boolean nextBlock(TurtleChunker.BlockConsumer consumer) throws IOException {
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
				case BLANK_NODE_LABEL -> parseBlankNodeLabelOneStep();
				case GRAPH_BLOCK_CLOSED -> parseGraphBlockClosedOneStep();
				default -> throw new TurtleChunker.TurtleSyntaxException();
			}

			if (pendingBytes != null && emitPending(consumer)) {
				return true;
			}
		}
	}

	private void parseDefaultOneStep() throws IOException {
		byte b = nextByte();
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
						throw new TurtleChunker.TurtleSyntaxException();
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
					throw new TurtleChunker.TurtleSyntaxException();
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
			throw new TurtleChunker.TurtleSyntaxException();
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
			byte b = nextByte();
			if (b == '\n' || b == '\r') {
				nextDefaultByteAtTokenBoundary = true;
				state = DEFAULT;
				return;
			}
		}
	}

	private void parseBlankNodeLabelOneStep() {
		byte b = nextByte();
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
			throw new TurtleChunker.TurtleSyntaxException();
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
				partialBytes.write(chunkBuf, chunkStart, bufPos - chunkStart);
			}
			pendingLength = partialBytes.size();
			pendingBytes = partialBytes.detachBytes();
			pendingOffset = 0;
			multiReadBlock = false;
		}

		pendingPrefixOrBase = currentBlockIsPrefixOrBase;
		pendingBlankNodeLabel = currentBlockHasBlankNodeLabel;
		pendingGraphHeader = inGraphBlock ? currentGraphHeader : null;
		chunkStart = bufPos;
	}

	private boolean emitPending(TurtleChunker.BlockConsumer consumer) throws IOException {
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

		consumer.accept(bytes, offset, length, prefixOrBase, blankNodeLabel, graphHeader);
		return true;
	}

	private boolean emitLeftoverAtEof(TurtleChunker.BlockConsumer consumer) throws IOException {
		if (inGraphBlock) {
			throw new TurtleChunker.TurtleSyntaxException();
		}
		if (!multiReadBlock && partialBytes.size() == 0) {
			return false;
		}

		pendingLength = partialBytes.size();
		pendingBytes = partialBytes.detachBytes();
		pendingOffset = 0;
		pendingPrefixOrBase = currentBlockIsPrefixOrBase;
		pendingBlankNodeLabel = currentBlockHasBlankNodeLabel;
		pendingGraphHeader = null;
		multiReadBlock = false;
		chunkStart = 0;

		return emitPending(consumer);
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
		int partialLength = multiReadBlock ? partialBytes.size() : 0;
		int currentLength = endExclusive - chunkStart;
		byte[] bytes = new byte[partialLength + currentLength];
		if (partialLength > 0) {
			System.arraycopy(partialBytes.array(), 0, bytes, 0, partialLength);
		}
		if (currentLength > 0) {
			System.arraycopy(chunkBuf, chunkStart, bytes, partialLength, currentLength);
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
		partialBytes.clear();
		multiReadBlock = false;
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
}
