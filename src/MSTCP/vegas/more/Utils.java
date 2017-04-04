package MSTCP.vegas.more;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Random;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public final class Utils {
    public static final int DATA_ENUM = 0;
    public static final int SYN_ENUM  = 1;
    public static final int FIN_ENUM  = 2;

    public static final boolean localhost    = true;
    public static final boolean delay        = true;
    public static final boolean drop         = false;
    public static final Random rand          = new Random();
    public static final int bufferSize       = 3;
    public static final int latency          = 1500;
    public static final int latency_variance = 1000;
    public static final double p_drop        = 0.05;

    public static final int noOfSources = 1;
    public static final int batchSize   = 1;   // to keep matrix sizes small we send blocks in groups of 15
    public static final int pktSize     = 1000; // 1000 Bytes total (Header 28 bytes, Block 972 bytes)
    public static final int tcpSize     = 28;   // TCP Header + timestams + no options
    public static final int moreSize    = 8;    // MORE Header with no code vector or data
    public static final int blockSize   = 800;  // size of data blocks
    public static final int maxVectorSize = pktSize - (tcpSize + moreSize + blockSize); // maximum size of code vector in bytes
    

    public static final int synAttempts  = Integer.MAX_VALUE;
    public static final int synTimeout   = Integer.MAX_VALUE;
    public static final int dataAttempts = 3;
    public static final int dataTimeout  = Integer.MAX_VALUE;
    public static final int finAttempts  = 3;
    public static final int finTimeout   = Integer.MAX_VALUE;

    public static final int total_alpha = 10;

    public static String getIPAddress(Logger logger) {
        if (localhost)
            return "127.0.0.1";
        
        URL req;
        BufferedReader in;
        String addr = null;

        try {
            req = new URL("http://checkip.amazonaws.com");
            in = new BufferedReader(new InputStreamReader(req.openStream()));
            addr = in.readLine(); // gets the IP as a string
        } catch (IOException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            System.exit(1);
        }

        return addr;
        
    }

    public static boolean drop() {
        if (drop)
            return (rand.nextDouble() < p_drop);

        return false;
    }

    public static void delay(Logger logger) {
        if (delay) {
            try {
                Thread.sleep(latency + rand.nextInt(latency_variance) - latency_variance / 2);
            } catch (InterruptedException e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
                System.exit(1);
            }
        }
    }

    public static Logger getLogger(String filename) {
        Logger logger = Logger.getLogger(filename);
        try {
            FileHandler handler = new FileHandler("./logs/" + filename + ".log", 1048576, 1, false);
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
