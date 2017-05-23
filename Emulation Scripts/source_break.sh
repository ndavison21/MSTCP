#!/bin/bash
cd ../src
javac -d ../bin MSTCP/vegas/more/*.java
cd ../bin

DIRECTORY="break"
FILE="test1mb.dat"
P_DROP=0.05
PACKET_LIMIT=200
SOURCES=2
PATHS=1

for CONNECTIONS in 1 2
    for BATCH_SIZE in 1 16
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

echo "We Done Here."
