package MSTCP.vegas.more;

import java.util.Vector;
import java.util.logging.Logger;

/**
 *  Handles the functionality for network coding
 */
public class NetworkCoder {
	final Logger logger;
	
	long fileSize   = -1;
    short nextBatch = 0; // the next batch of block to receive
    short baseBlock = 0; // the block number of the first block in the batch being received
    
    Vector<CodeVectorElement[]> codeVectorMatrix = new Vector<CodeVectorElement[]>();
    Vector<MOREPacket> packetBuffer = new Vector<MOREPacket>();
	
	
	public NetworkCoder(Logger logger, long fileSize) {
		this.logger = logger;
		this.fileSize = fileSize;
	}
	
	public boolean isInnovative(MOREPacket more) {
		CodeVectorElement[] codeVector = more.getCodeVector();
		short block = codeVector[0].getBlock();
		if (block < baseBlock)
			return false;
		for (CodeVectorElement[] ca: codeVectorMatrix) {
			if (block == ca[0].getBlock())
				return false;
		}
		
		codeVectorMatrix.add(codeVector);
    	packetBuffer.add(more);
		return true;
	}
	
	public boolean canDecode() {
		return codeVectorMatrix.size() == Utils.batchSize;
	}
	
	public Vector<byte[]> decode() {
		if (!canDecode())
			return null;
		
		logger.info("Received batch " + nextBatch + " (base block " + baseBlock + ")");
		Vector<byte[]> packets = new Vector<byte[]>();
		for (MOREPacket more: packetBuffer)
			packets.add(more.getEncodedData().toByteArray());
		
		codeVectorMatrix.clear();
		packetBuffer.clear();
		nextBatch++;
		baseBlock+= Utils.batchSize;
		
		return packets;
	}
	
    
    public short random() {
    	// returns a random element of the field
    	return (short) 1;
    }
}
