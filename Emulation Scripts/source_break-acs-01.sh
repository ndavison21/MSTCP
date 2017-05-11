#!/bin/bash
cd ../src
javac -d ../bin MSTCP/vegas/more/*.java
cd ../bin

DIRECTORY="break"
FILE="me.jpg"
P_DROP=0.05
PACKET_LIMIT=300
SOURCES=2

BATCH_SIZE=16

for PATHS in 1 2
do
    for CONNECTIONS in 1 2
    do
        if [ "$CONNECTIONS" -lt "$(($SOURCES * $PATHS))" ]
        then
            path="../evaluation/data/${DIRECTORY}/s${SOURCES}_p${PATHS}_c${CONNECTIONS}_b${BATCH_SIZE}_p${P_DROP}/"
            echo $path
            rm -r $path
            mkdir -p $path
            for i in {0..9}
            do
                java -Djava.util.logging.SimpleFormatter.format='%5$s%6$s%n' MSTCP.vegas.more.Experiment $DIRECTORY $SOURCES $PATHS $CONNECTIONS $BATCH_SIZE $P_DROP $PACKET_LIMIT $i $FILE
            done
        fi
    done
done

echo "We Done Here."
