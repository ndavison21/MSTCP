package MSTCP.vegas.more;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Random;
import java.util.Vector;
import java.util.logging.Logger;


/**
 *  Handles the functionality for network coding
 */
public class BatchedNetworkCoder {
	final Logger logger;
	
	long fileSize   = -1;
    short nextBatch = 0; // the next batch of block to receive
    short baseBlock = 0; // the block number of the first block in the batch being received
    
    int dofs = 0; // number of degrees of freedom for current batch
    Boolean canDecode = false;
    
    short[][] codeVectorMatrix = new short[Utils.batchSize][]; // matrix in row echelon form for checking innovativeness
    BigDecimal[][] s = new BigDecimal[Utils.batchSize][]; // matrix of coding vectors to be inverted
    BigInteger[] packetBuffer = new BigInteger[Utils.batchSize];
    
    Random random = new Random();
	
	
	public BatchedNetworkCoder(Logger logger, long fileSize) {
		this.logger = logger;
		this.fileSize = fileSize;
	}
	
	public boolean isInnovative(MOREPacket more) {
		CodeVectorElement[] codeVector = more.getCodeVector();
		
		if (codeVector[0].getBlock() < baseBlock) // packet is for previous batch: don't need
		    return false;
		double[] coefficients = new double[Utils.batchSize];
		for (CodeVectorElement c: codeVector) {
		    coefficients[c.getBlock() - baseBlock] = c.getCoefficient();
		}
		
		for (short i=0; i < Utils.batchSize; i++) {
		    if (coefficients[i] != 0) {
		        if (codeVectorMatrix[i] != null) {
		            for (short j=0; j < Utils.batchSize; j++) // u <- u - M[i]u[i]
		                coefficients[j] -= codeVectorMatrix[i][j] * coefficients[i];
		        } else {
		            synchronized (canDecode) {
    		            // admit the block into memory
    		            for(short j=0; j< Utils.batchSize; j++)
    		                coefficients[j] /= coefficients[i];
    		            s[dofs] = new BigDecimal[Utils.batchSize];
                        for (CodeVectorElement c: more.getCodeVector()) {
                            s[dofs][c.getBlock() - baseBlock] = new BigDecimal(c.getCoefficient());
                        }
                        packetBuffer[dofs++] = more.getEncodedData();
                    	canDecode = (dofs == Utils.batchSize);
                    	if (canDecode) {
                            nextBatch++;
                            baseBlock+= Utils.batchSize;
                            logger.info("Can decode batch " + nextBatch + " (base block " + baseBlock + ")");
                    	}
		            }
            		return true;
		        }
		    }
		}
		
		return false;
	}
	
	public boolean canDecode() {
		return canDecode;
	}
	
	public Vector<byte[]> decode() {
		if (!canDecode())
			return null;
		
		BigDecimal[][] s_inverse = invert(s);
		
		Vector<byte[]> blocks = new Vector<byte[]>();
		BigDecimal block;
		for (int i=0; i < Utils.batchSize; i++) {
		    block = new BigDecimal(0);
		    for (int j=0; j < Utils.batchSize; j++) {
		        // System.out.println(s_inverse[i][j] + " " + 1.0/s_inverse[i][j] + " " + BigDecimal.valueOf(s_inverse[i][j]));
		        BigDecimal co = s_inverse[i][j];
		        BigDecimal blk = new BigDecimal(packetBuffer[j]);
		        block = block.add( blk.multiply(co) );
		    }
		    blocks.add(block.toBigInteger().toByteArray());
		}
				
		for(int i=0; i<Utils.batchSize; i++) {
		    codeVectorMatrix[i] = null;
		    s[i] = null;
		    packetBuffer[i] = null;
		}
		
		synchronized(canDecode) {
		    dofs = 0;
            canDecode = false;
		}
		
		return blocks;
	}
	
    
    public short random() {
    	return (short) ( random.nextInt( (2 * Byte.MAX_VALUE) + 1) - Byte.MAX_VALUE );
    }
    
    private static BigDecimal[][] invert(BigDecimal a[][]) {
        int n = a.length;
        BigDecimal x[][] = new BigDecimal[n][n];
        BigDecimal b[][] = new BigDecimal[n][n];
        int index[] = new int[n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                b[i][j] = new BigDecimal(i == j ? 1 : 0);
        // Transform the matrix into an upper triangle
        gaussian(a, index);
        // Update the matrix b[i][j] with the ratios stored
        for (int i = 0; i < n - 1; ++i)
            for (int j = i + 1; j < n; ++j)
                for (int k = 0; k < n; ++k)
                    b[index[j]][k] = b[index[j]][k].subtract(a[index[j]][i].multiply(b[index[i]][k]));
        // Perform backward substitutions
        for (int i = 0; i < n; ++i) {
            x[n - 1][i] = b[index[n - 1]][i].divide(a[index[n - 1]][n - 1], Utils.precision, RoundingMode.HALF_UP);
            for (int j = n - 2; j >= 0; --j) {
                x[j][i] = b[index[j]][i];
                for (int k = j + 1; k < n; ++k) {
                    x[j][i] = x[j][i].subtract(a[index[j]][k].multiply(x[k][i]));
                }
                x[j][i] = x[j][i].divide(a[index[j]][j], Utils.precision, RoundingMode.HALF_UP);
            }
        }
        return x;
    }

    // Method to carry out the partial-pivoting Gaussian
    // elimination. Here index[] stores pivoting order.
    public static void gaussian(BigDecimal a[][], int index[]) {
        int n = index.length;
        BigDecimal c[] = new BigDecimal[n];
        // Initialize the index
        for (int i = 0; i < n; ++i)
            index[i] = i;
        // Find the rescaling factors, one from each row
        for (int i = 0; i < n; ++i) {
            BigDecimal c1 = new BigDecimal("0");
            for (int j = 0; j < n; ++j) {
                BigDecimal c0 = (a[i][j]).abs();
                if (c0.compareTo(c1) > 0)
                    c1 = c0;
            }
            c[i] = c1;
        }
        // Search the pivoting element from each column
        int k = 0;
        for (int j = 0; j < n - 1; ++j) {
            BigDecimal pi1 = new BigDecimal("0");
            for (int i = j; i < n; ++i) {
                BigDecimal pi0 = a[index[i]][j].abs();
                pi0 = pi0.divide(c[index[i]], Utils.precision, RoundingMode.HALF_UP);
                if (pi0.compareTo(pi1) > 0) {
                    pi1 = pi0;
                    k = i;
                }
            }
            // Interchange rows according to the pivoting order
            int itmp = index[j];
            index[j] = index[k];
            index[k] = itmp;
            for (int i = j + 1; i < n; ++i) {
                BigDecimal pj = a[index[i]][j].divide(a[index[j]][j], Utils.precision, RoundingMode.HALF_UP);
                // Record pivoting ratios below the diagonal
                a[index[i]][j] = pj;
                // Modify other elements accordingly
                for (int l = j + 1; l < n; ++l)
                    a[index[i]][l] = a[index[i]][l].subtract(pj.multiply(a[index[j]][l]));
            }
        }
    }
}
