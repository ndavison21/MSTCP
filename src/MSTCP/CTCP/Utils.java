package MSTCP.CTCP;

import java.io.IOException;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Parameters and functions for operation
 */
public final class Utils {
    /** Development **/
    public static final boolean debug = true; 
    
    /** Network Parameter Estimations **/
    public static final double rtt = 3000; // default RTT of connections
    public static final double p = 0.1; // default packet drop rate
    
    /** Receiver Parameters  **/
    public static final int headerSize  = 28;  // TCP Header (with no options): 20 bytes
    public static final int requestSize = 32;  // TCP Header: 20 , Request (linear combination to send): 4 bytes
    public static final int pktSize     = 1000; // TCP Header: 20, blocks <= 980, so 1000 Bytes total
    public static final int pktDataSize = pktSize - headerSize;  // Send blocks of 980 bytes
    public static final int synLimit    = 50;   // number if times to try SYN before giving up
    public static final int noOfSources = 2;    // number of sources to connect to
    
    
    
    public static Logger getLogger(String classname, String filename) {
        Logger logger = Logger.getLogger(classname);
        try {
            FileHandler handler = new FileHandler("./logs/" + filename, 1048576, 1, false);
            handler.setFormatter(new SimpleFormatter());
            logger.setUseParentHandlers(false);
            logger.addHandler(handler);
            logger.setLevel(Level.ALL);
        } catch (SecurityException | IOException e) {
            System.err.println("MSTCPReceiver: Unable to Connect to Logger");
            e.printStackTrace();
            return null;
        }
        return logger;
    }
}
