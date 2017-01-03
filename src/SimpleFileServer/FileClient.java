package SimpleFileServer;

import java.io.DataInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;

/**
 * 
 * @author Nathanael Davison - nd359
 *
 *      Simple file client which requests a file by name and stores it locally
 *
 */
public class FileClient {
    private static Socket socket;  // communication with server
    private static PrintStream os; // for writing to disc
    private static String filename = "hello.txt";
	
    public static void main(String[] args) throws IOException {
        OutputStream output = null;
        DataInputStream clientData = null;
        
        try {
            int port = 14415;
            String hostname = "127.0.0.1";
            socket = new Socket(hostname, port); // connects to hostname on port
            System.out.println("FileClient: Connected to " + hostname + " on port " + port);
			
            os = new PrintStream(socket.getOutputStream());
            os.println(filename);
			
            int bytesRead;
            clientData = new DataInputStream(socket.getInputStream());
            
            if (clientData.readBoolean()) { // see if server was able to find the file
                output = new FileOutputStream("received_" + filename);
                long size = clientData.readLong(); // length of file
                byte[] buffer = new byte[4096];
                while (size > 0 && (bytesRead = clientData.read(buffer, 0, (int) Math.min(buffer.length, size))) != -1) {
                    output.write(buffer, 0, bytesRead);
                    size -= bytesRead;
                }
                System.out.println("FileClient: Finished reading file " + filename);
            } else {
                System.out.println("FileClient: Server was unable to find file: " + filename); // fail gracefully
            }
        } catch (IOException e) {
            System.err.println("FileClient: Error receiving file from server");
            e.printStackTrace();
        } finally {
            if (output != null) output.close();
            if (clientData != null) clientData.close();
        }
    }
}
