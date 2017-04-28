package MSTCP.vegas.more;

import java.nio.ByteBuffer;

public class CodeVectorElement {
	private short block;
	private int coefficient;
	private double innovCoefficient;
	
	public CodeVectorElement(short block, int coefficient) {
		this.block = block;
		this.coefficient = coefficient;
		this.innovCoefficient = coefficient;
	}
	
	public CodeVectorElement(byte[] bytes) {
		ByteBuffer bb = ByteBuffer.wrap(bytes);
		this.block = bb.getShort();
		this.coefficient = bb.getInt();
		this.innovCoefficient = this.coefficient;
	}

	public short getBlock() {
		return block;
	}

	public int getCoefficient() {
		return coefficient;
	}
	
	public void setBlock(short block) {
        this.block = block;
    }

    public void setCoefficient(int coefficeint) {
        this.coefficient = coefficeint;
        this.innovCoefficient = coefficient;
    }

    public double getInnovCoefficient() {
        return innovCoefficient;
    }

    public void setInnovCoefficient(double innovCoefficient) {
        this.coefficient = (int) innovCoefficient;
        this.innovCoefficient = innovCoefficient;
    }

    public byte[] bytes() {
		ByteBuffer bb = ByteBuffer.allocate(6);
		bb.putShort(block);
		bb.putInt(coefficient);
		return bb.array();
	}
    
}
