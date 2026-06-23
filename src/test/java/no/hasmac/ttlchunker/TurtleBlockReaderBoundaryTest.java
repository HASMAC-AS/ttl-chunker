package no.hasmac.ttlchunker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TurtleBlockReaderBoundaryTest {

	@Test
	void smallReadsPreserveLexicalBoundaries() throws IOException {
		String input = """
				@prefix ex: <http://example.com/really/long/> .
				ex:s <http://example.com/iri/with{brace}> "escaped \\" literal with . and }" .
				ex:s ex:multi '''line one
				line two . } still literal''' .
				# comment . } ignored
				_:shared ex:p "blank" .
				ex:tail ex:p "period eof" .""";

		List<Block> blocks = collect(input, 7);

		assertEquals(5, blocks.size());
		assertBlock(blocks.get(0), "@prefix ex: <http://example.com/really/long/> .", true, false, null);
		assertBlock(blocks.get(1),
				"ex:s <http://example.com/iri/with{brace}> \"escaped \\\" literal with . and }\" .",
				false, false, null);
		assertBlock(blocks.get(2),
				"ex:s ex:multi '''line one\nline two . } still literal''' .",
				false, false, null);
		assertBlock(blocks.get(3), "# comment . } ignored\n_:shared ex:p \"blank\" .", false, true, null);
		assertBlock(blocks.get(4), "ex:tail ex:p \"period eof\" .", false, false, null);
	}

	@Test
	void smallReadsAttachGraphHeaderToTrigStatement() throws IOException {
		String input = """
				@prefix ex: <http://example.com/> .
				GRAPH ex:graphName {
				  ex:s ex:p "value" .
				} .""";

		List<Block> blocks = collect(input, 5);

		assertEquals(2, blocks.size());
		assertBlock(blocks.get(0), "@prefix ex: <http://example.com/> .", true, false, null);
		assertBlock(blocks.get(1), "ex:s ex:p \"value\" .", false, false, "GRAPH ex:graphName {");
	}

	@Test
	void periodAndDirectiveAtEofEmitBlocks() throws IOException {
		List<Block> directiveBlocks = collect("@prefix ex: <http://example.com/> .", 4);
		assertEquals(1, directiveBlocks.size());
		assertTrue(directiveBlocks.getFirst().prefixOrBase());
		assertEquals("@prefix ex: <http://example.com/> .", directiveBlocks.getFirst().text());

		List<Block> statementBlocks = collect("ex:s ex:p ex:o .", 4);
		assertEquals(1, statementBlocks.size());
		assertFalse(statementBlocks.getFirst().prefixOrBase());
		assertEquals("ex:s ex:p ex:o .", statementBlocks.getFirst().text());
	}

	private static void assertBlock(Block block, String text, boolean prefixOrBase, boolean blankNodeLabel,
			String graphHeader) {
		assertEquals(text, block.text());
		assertEquals(prefixOrBase, block.prefixOrBase());
		assertEquals(blankNodeLabel, block.blankNodeLabel());
		if (graphHeader == null) {
			assertNull(block.graphHeader());
		} else {
			assertEquals(graphHeader, block.graphHeader());
		}
	}

	private static List<Block> collect(String input, int bufferSize) throws IOException {
		ByteArrayInputStream bytes = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
		TurtleBlockReader reader = new TurtleBlockReader(bytes, bufferSize);
		List<Block> blocks = new ArrayList<>();
		reader.forEachBlock(new TurtleChunker.BlockConsumer() {
			@Override
			public void accept(byte[] bytes, int offset, int length, boolean prefixOrBase) {
				accept(bytes, offset, length, prefixOrBase, false, null);
			}

			@Override
			public void accept(byte[] bytes, int offset, int length, boolean prefixOrBase,
					boolean blankNodeLabel, byte[] graphHeader) {
				String text = new String(bytes, offset, length, StandardCharsets.UTF_8);
				String graph = graphHeader == null ? null : new String(graphHeader, StandardCharsets.UTF_8);
				blocks.add(new Block(text, prefixOrBase, blankNodeLabel, graph));
			}
		});
		return blocks;
	}

	private record Block(String text, boolean prefixOrBase, boolean blankNodeLabel, String graphHeader) {
	}
}
