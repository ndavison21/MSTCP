package MSTCP.vegas.more;

import java.nio.ByteBuffer;

public class CodeVectorElement {
	private final short block;
	private final short coefficeint;
	
	public CodeVectorElement(short block, short coefficient) {
		this.block = block;
		this.coefficeint = coefficient;
	}
	
	public CodeVectorElement(byte[] bytes) {
		ByteBuffer bb = ByteBuffer.wrap(bytes);
		this.block = bb.getShort();
		this.coefficeint = bb.getShort();
	}

	public short getBlock() {
		return block;
	}

	public short getCoefficeint() {
		return coefficeint;
	}
	
	public byte[] bytes() {
		ByteBuffer bb = ByteBuffer.allocate(4);
		bb.putShort(block);
		bb.putShort(coefficeint);
		return bb.array();
	}
}
