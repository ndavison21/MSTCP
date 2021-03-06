package MSTCP.vegas.more;

import java.nio.ByteBuffer;
import java.util.Vector;

public class MSTCPInformation {

    byte[] recvAddr;
    int recvPort;    // can uniquely identify the connection by the port it receives on
    int flowID;
    String filename;
    long fileSize = -1;
    Vector<SourceInformation> sources;

    
    // For sending with SYN
    public MSTCPInformation(byte[] recvAddr, int recvPort, String filename, int flowID) {
        this(null, recvAddr, recvPort, filename, -1, flowID);
    }
    
    // For sending with SYN + ACK
    public MSTCPInformation(Vector<SourceInformation> sources, byte[] recvAddr, int recvPort, String filename, long filesize, int flowID) {
        super();
        this.sources  = sources;
        this.recvAddr = recvAddr;
        this.recvPort = recvPort;
        this.flowID   = flowID;
        this.filename = filename;
        this.fileSize = filesize;
    }
    
    public MSTCPInformation(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        this.recvAddr = new byte[4];
        bb.get(recvAddr);
        this.recvPort = bb.getInt();
        this.flowID = bb.getInt();
        this.fileSize = bb.getLong();
        int filenameLength = bb.getInt();
        byte[] filenameBytes = new byte[filenameLength];
        bb.get(filenameBytes);
        this.filename = new String(filenameBytes);
        
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
    
    public void update(MSTCPInformation mstcpInformation) {
        if (fileSize == -1)
            fileSize = mstcpInformation.fileSize;
        
        if (this.sources == null)
            this.sources = mstcpInformation.sources;
        else {
            for (SourceInformation s: mstcpInformation.sources) {
                if (!this.sources.contains(s))
                    this.sources.add(s);
            }
        }
    }
    
    public byte[] bytes() {
        int size = 0;
        size += 4; // for address (InetAddress)
        size += 4; // for receiver port (int) 
        size += 4; // for flowID
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
        bb.put(recvAddr);
        bb.putInt(recvPort);
        bb.putInt(flowID);
        bb.putLong(fileSize);
        
        bb.putInt(filename.getBytes().length);
        bb.put(filename.getBytes());
        
        bb.putInt(sources == null ? 0 : sources.size());
        if (sources != null) {
            for (byte[] s: sourceBytes) {
                bb.putInt(s.length);
                bb.put(s);
            }
        }
        
        
        return bb.array();
        
    }
    
    public static int getFlowID(byte[] mstcpBytes) {
        return ByteBuffer.wrap(mstcpBytes, 8, 4).getInt();
    }
    
    public static long getFileSize(byte[] mstcpBytes) {
        return ByteBuffer.wrap(mstcpBytes, 12, 8).getLong();
    }


    
    
}
