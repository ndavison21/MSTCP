#!/bin/bash
cd ../src
javac -d ../bin MSTCP/vegas/more/*.java
cd ../bin

DIRECTORY="timing"
FILE="test1mb.dat"
PACKET_LIMIT=-1

for SOURCES in 2
do
    for PATHS in 1 2
    do
        CONNECTIONS="$(($SOURCES * $PATHS))"
        if [ "$CONNECTIONS" -ge "$PATHS" ]
        then
            for BATCH_SIZE in 1 16 64
            do
                for P_DROP in "0.00" "0.05"
                do
                    path="../evaluation/data/${DIRECTORY}/s${SOURCES}_p${PATHS}_c${CONNECTIONS}_b${BATCH_SIZE}_p${P_DROP}/"
                    echo $path
                    rm -r $path
                    mkdir -p $path
                    for i in {0..999}
                    do
                        java -Djava.util.logging.SimpleFormatter.format='%5$s%6$s%n' MSTCP.vegas.more.Experiment $DIRECTORY $SOURCES $PATHS $CONNECTIONS $BATCH_SIZE $P_DROP $PACKET_LIMIT $i $FILE
                    done
                done
            done
        fi
    done
done

echo "We Done Here."
