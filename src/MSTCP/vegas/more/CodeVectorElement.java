package MSTCP.vegas.more;

import java.nio.ByteBuffer;

public class CodeVectorElement {
	private int block;
	private short coefficient;
	private double innovCoefficient;
	
	public CodeVectorElement(int block, short coefficient) {
		this.block = block;
		this.coefficient = coefficient;
		this.innovCoefficient = coefficient;
	}
	
	public CodeVectorElement(byte[] bytes) {
		ByteBuffer bb = ByteBuffer.wrap(bytes);
		this.block = bb.getInt();
		this.coefficient = bb.getShort();
		this.innovCoefficient = this.coefficient;
	}

	public int getBlock() {
		return block;
	}

	public int getCoefficient() {
		return coefficient;
	}
	
	public void setBlock(int block) {
        this.block = block;
    }

    public void setCoefficient(short coefficeint) {
        this.coefficient = coefficeint;
        this.innovCoefficient = coefficient;
    }

    public double getInnovCoefficient() {
        return innovCoefficient;
    }

    public void setInnovCoefficient(double innovCoefficient) {
        this.coefficient = (short) innovCoefficient;
        this.innovCoefficient = innovCoefficient;
    }

    public byte[] bytes() {
		ByteBuffer bb = ByteBuffer.allocate(6);
		bb.putInt(block);
		bb.putShort(coefficient);
		return bb.array();
	}
    
}
