#!/bin/bash
cd ../src
javac -d ../bin MSTCP/vegas/more/*.java
cd ../bin

DIRECTORY="batch"
FILE="gb.jpg"
PACKET_LIMIT=-1

SOURCES=2
PATHS=2
CONNECTIONS=4

for BATCH_SIZE in 1 2 4 8 16 64 128 256
do
    for P_DROP in $(seq 0 0.01 0.2)
    do
        path="../evaluation/data/${DIRECTORY}/s${SOURCES}_p${PATHS}_c${CONNECTIONS}_b${BATCH_SIZE}_p${P_DROP}/"
        echo $path
        rm -r $path
        mkdir -p $path
        for i in {0..9}
        do
            java -Djava.util.logging.SimpleFormatter.format='%5$s%6$s%n' MSTCP.vegas.more.Experiment $DIRECTORY $SOURCES $PATHS $CONNECTIONS $BATCH_SIZE $P_DROP $PACKET_LIMIT $i $FILE
        done    
    done
done

echo "We Done Here."