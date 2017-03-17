package MSTCP.reno;

import java.nio.ByteBuffer;
import java.util.Vector;

public class MSTCPInformation {

    int receiverID;
    String filename;
    long fileSize = -1;    
    Vector<SourceInformation> sources;

    
    // For sending with SYN
    public MSTCPInformation(int connectionID, String filename) {
        this.receiverID = connectionID;
        this.filename = filename;
    }
    
    // For sending with SYN + ACK
    public MSTCPInformation(Vector<SourceInformation> sources, int connectionID, String filename, long filesize) {
        this.sources = sources;
        this.receiverID = connectionID;
        this.filename = filename;
        this.fileSize = filesize;
    }
    
    public MSTCPInformation(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        this.receiverID = bb.getInt();
        int filenameLength = bb.getInt();
        byte[] filenameBytes = new byte[filenameLength];
        bb.get(filenameBytes);
        this.filename = new String(filenameBytes);
        this.fileSize = bb.getLong();
        
        int noOfSources = bb.getInt();
        if (noOfSources > 0) {
            sources = new Vector<SourceInformation>(noOfSources);
            byte[] sourceInfoBytes;
            for (int i=0; i < noOfSources; i++) {
                int len = bb.getInt();
                sourceInfoBytes = new byte[len];
                bb.get(sourceInfoBytes);
                sources.add(new SourceInformation(sourceInfoBytes));
            }            
        }

    }
    
    public byte[] bytes() {
        int size = 0;
        size += 4; // for receiver ID (int) 
        size += 4; // for length of filename (int)
        size += filename.getBytes().length; // for filename (String -> bytes)
        size += 8; // for filesize (long)
        
        size += 4; // for number of sources
        
        Vector<byte[]> sourceBytes = null;
        if (sources != null) {      
            sourceBytes = new Vector<byte[]>(sources.size());
            byte[] sBytes;
            for (SourceInformation s: sources) {
                sBytes = s.bytes();
                sourceBytes.add(s.bytes());
                size += 4; // for length of source information (int)
                size += sBytes.length; // for source information (SourceInformation -> Bytes)
            }
        }
        
        
        ByteBuffer bb = ByteBuffer.allocate(size);
        bb.putInt(receiverID);
        bb.putInt(filename.getBytes().length);
        bb.put(filename.getBytes());
        
        bb.putLong(fileSize);
        bb.putInt(sources == null ? 0 : sources.size());
        if (sources != null) {
            for (byte[] s: sourceBytes) {
                bb.putInt(s.length);
                bb.put(s);
            }
        }
        
        
        return bb.array();
        
    }
    
    
}
