package MSTCP.vegas;

public class ConnectionToRequestMap {
    public int connection; // port of the connection (unique identifier)
    public int block;
    
    public ConnectionToRequestMap(int connection, int block) {
        this.connection = connection;
        this.block = block;
    }
}
