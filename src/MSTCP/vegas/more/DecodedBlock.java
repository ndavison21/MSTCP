package MSTCP.vegas.more;

public class DecodedBlock {
    public int block;
    public byte[] data;
    
    public DecodedBlock(int block, byte[] data) {
        this.block = block;
        this.data = data;
    }
}
