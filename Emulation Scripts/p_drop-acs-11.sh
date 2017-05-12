#!/bin/bash
cd ../src
javac -d ../bin MSTCP/vegas/more/*.java
cd ../bin

DIRECTORY="timing"
FILE="me.jpg"
PACKET_LIMIT=-1

P_DROP=0.01

for SOURCES in 1 2
do
    for PATHS in 1 2
    do
        CONNECTIONS="$(($SOURCES * $PATHS))"
        for BATCH_SIZE in 1 16 64
        do
            path="../evaluation/data/${DIRECTORY}/s${SOURCES}_p${PATHS}_c${CONNECTIONS}_b${BATCH_SIZE}_p${P_DROP}/"
            echo $path
            rm -r $path
            mkdir -p $path
            for i in {10..99}
            do
                java -Djava.util.logging.SimpleFormatter.format='%5$s%6$s%n' MSTCP.vegas.more.Experiment $DIRECTORY $SOURCES $PATHS $CONNECTIONS $BATCH_SIZE $P_DROP $PACKET_LIMIT $i $FILE
            done      
        done
    done
done

echo "We Done Here."
