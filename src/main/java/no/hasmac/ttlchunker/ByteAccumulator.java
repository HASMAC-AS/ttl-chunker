package no.hasmac.ttlchunker;

final class ByteAccumulator {
	private final int initialCapacity;
	private byte[] bytes;
	private int size;

	ByteAccumulator(int initialCapacity) {
		this.initialCapacity = Math.max(1, initialCapacity);
		bytes = new byte[this.initialCapacity];
	}

	void write(int b) {
		ensureCapacity(size + 1);
		bytes[size++] = (byte) b;
	}

	void write(byte[] source, int offset, int length) {
		if (length <= 0) {
			return;
		}
		ensureCapacity(size + length);
		System.arraycopy(source, offset, bytes, size, length);
		size += length;
	}

	byte[] detachBytes() {
		byte[] detached = bytes;
		bytes = new byte[initialCapacity];
		size = 0;
		return detached;
	}

	byte[] array() {
		return bytes;
	}

	int size() {
		return size;
	}

	void clear() {
		size = 0;
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
