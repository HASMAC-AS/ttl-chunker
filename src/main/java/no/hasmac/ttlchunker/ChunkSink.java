package no.hasmac.ttlchunker;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.LongSupplier;

final class ChunkSink implements TurtleChunker.BlockConsumer, AutoCloseable {
	private static final int BUFFER_SIZE = 1024 * 1024 * 4;
	private static final int PREFIX_BUFFER_INITIAL_SIZE = 8 * 1024;

	private final Path outputDir;
	private final long approximateChunkSizeBytes;
	private final boolean printStatus;
	private final PrintStream statusOutput;
	private final LongSupplier currentTimeMillis;
	private final String chunkFileExtension;
	private final ByteAccumulator prefixes = new ByteAccumulator(PREFIX_BUFFER_INITIAL_SIZE);

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

	ChunkSink(Path outputDir, long approximateChunkSizeBytes, boolean printStatus, PrintStream statusOutput,
			LongSupplier currentTimeMillis, String chunkFileExtension) {
		this.outputDir = outputDir;
		this.approximateChunkSizeBytes = approximateChunkSizeBytes;
		this.printStatus = printStatus;
		this.statusOutput = statusOutput;
		this.currentTimeMillis = currentTimeMillis;
		this.chunkFileExtension = chunkFileExtension;
	}

	@Override
	public void accept(byte[] bytes, int offset, int length, boolean prefixOrBase) throws IOException {
		accept(bytes, offset, length, prefixOrBase, false);
	}

	@Override
	public void accept(byte[] bytes, int offset, int length, boolean prefixOrBase, boolean blankNodeLabel)
			throws IOException {
		accept(bytes, offset, length, prefixOrBase, blankNodeLabel, null);
	}

	@Override
	public void accept(byte[] bytes, int offset, int length, boolean prefixOrBase, boolean blankNodeLabel,
			byte[] graphHeader) throws IOException {
		if (prefixOrBase) {
			prefixes.write(bytes, offset, length);
			prefixes.write('\n');
			return;
		}

		if (length == 0) {
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
		currentOutput.write(bytes, offset, length);
		currentOutput.write('\n');
		currentChunkBytes += length + 1L;
		statements++;

		if (currentChunkBytes > approximateChunkSizeBytes) {
			closeCurrentOutput();
		}
	}

	private void writeBlankNodeStatement(byte[] bytes, int offset, int length, byte[] graphHeader)
			throws IOException {
		if (blankNodeOutput == null) {
			openBlankNodeChunk();
		}

		switchBlankNodeGraph(graphHeader);
		blankNodeOutput.write(bytes, offset, length);
		blankNodeOutput.write('\n');
		statements++;
	}

	private void openNextChunk() throws IOException {
		chunkIndex++;
		Path chunkPath = outputDir.resolve(formatChunkFileName(chunkIndex, chunkFileExtension));
		currentChunkPath = chunkPath;
		currentChunkStartedMillis = currentTimeMillis.getAsLong();
		currentOutput = new BufferedOutputStream(Files.newOutputStream(chunkPath), BUFFER_SIZE);
		currentChunkBytes = writeChunkHeader(currentOutput, prefixes);
	}

	private void openBlankNodeChunk() throws IOException {
		chunkIndex++;
		Path chunkPath = outputDir.resolve(formatChunkFileName(chunkIndex, chunkFileExtension));
		blankNodeChunkPath = chunkPath;
		blankNodeChunkStartedMillis = currentTimeMillis.getAsLong();
		blankNodeOutput = new BufferedOutputStream(Files.newOutputStream(chunkPath), BUFFER_SIZE);
		writeChunkHeader(blankNodeOutput, prefixes);
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

	@Override
	public void close() throws IOException {
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

	long statements() {
		return statements;
	}

	int chunkCount() {
		return chunkIndex;
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
}
